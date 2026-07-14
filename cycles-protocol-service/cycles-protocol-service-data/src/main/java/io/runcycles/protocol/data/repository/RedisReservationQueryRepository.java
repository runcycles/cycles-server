package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.support.ReservationComparators;
import io.runcycles.protocol.data.repository.support.ReservationListQuery;
import io.runcycles.protocol.data.repository.support.ScanPageCursor;
import io.runcycles.protocol.data.repository.support.SortedListCursor;
import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import io.runcycles.protocol.data.util.HashProjections;
import io.runcycles.protocol.data.util.LogSanitizer;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ReservationListResponse;
import io.runcycles.protocol.model.ReservationSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes reservation-list queries while the public repository remains the
 * protocol-facing facade.
 *
 * <p>Both the authoritative SCAN path and the bounded created-at index path
 * consume the same immutable query and the same hash mapper. This component is
 * intentionally package-scoped in responsibility: it does not create or
 * mutate reservations and it owns no wire-facing API.
 */
@Component
public class RedisReservationQueryRepository {
    private static final Logger LOG = LoggerFactory.getLogger(RedisReservationQueryRepository.class);

    static final int SORTED_HYDRATE_WARN_THRESHOLD = 2000;
    static final int SORTED_INDEX_BATCH_SIZE = 128;

    private final JedisPool jedisPool;
    private final CyclesMetrics metrics;
    private final ReservationHashMapper hashMapper;

    @Autowired
    public RedisReservationQueryRepository(JedisPool jedisPool, CyclesMetrics metrics,
                                           ReservationHashMapper hashMapper) {
        this.jedisPool = jedisPool;
        this.metrics = metrics;
        this.hashMapper = hashMapper;
    }

    public ReservationListResponse list(ReservationListQuery query,
                                        ReservationCreatedAtIndexService createdAtIndex) {
        Optional<SortedListCursor> parsedCursor = SortedListCursor.decode(query.startCursor());
        if ((parsedCursor.isPresent() && !parsedCursor.get().hasValidBoundary())
                || (query.sortRequested() && query.startCursor() != null
                    && !query.startCursor().isBlank() && parsedCursor.isEmpty())) {
            throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                "cursor contains an invalid sorted-list boundary", 400);
        }

        if (query.sortRequested() || parsedCursor.isPresent()) {
            return listSorted(query, parsedCursor.orElse(null), createdAtIndex);
        }
        return listScanPage(query);
    }

    private ReservationListResponse listScanPage(ReservationListQuery query) {
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("reservation:res_*").count(100);
            List<ReservationSummary> result = new ArrayList<>();
            List<String> projection = ReservationHashMapper.projection(query.include(), false);
            String[] projectionFields = projection.toArray(String[]::new);
            ScanPageCursor pageCursor = ScanPageCursor.decode(query.startCursor());
            String cursor = pageCursor.redisCursor();
            int batchOffset = pageCursor.offset();

            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                List<String> keys = scan.getResult();
                if (!keys.isEmpty()) {
                    Map<String, Response<List<String>>> responses = new HashMap<>();
                    try (Pipeline pipeline = jedis.pipelined()) {
                        for (String key : keys) {
                            responses.put(key, pipeline.hmget(key, projectionFields));
                        }
                        pipeline.sync();
                    }

                    int startIndex = Math.min(batchOffset, keys.size());
                    batchOffset = 0;
                    for (int i = startIndex; i < keys.size(); i++) {
                        String key = keys.get(i);
                        try {
                            Map<String, String> fields = HashProjections.mapHashFields(
                                projection, responses.get(key).get());
                            if (fields.isEmpty()) continue;
                            ReservationSummary summary = hashMapper.matchingSummary(fields, query);
                            if (summary == null) continue;
                            result.add(summary);
                            if (result.size() >= query.limit()) {
                                String nextCursor = ScanPageCursor.nextCursor(
                                    cursor, i + 1, keys.size(), scan.getCursor());
                                return ReservationListResponse.builder()
                                    .reservations(result)
                                    .hasMore(nextCursor != null)
                                    .nextCursor(nextCursor)
                                    .build();
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to parse reservation: {}",
                                LogSanitizer.sanitize(key), e);
                        }
                    }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));

            return ReservationListResponse.builder()
                .reservations(result).hasMore(false).nextCursor(null).build();
        }
    }

    private ReservationListResponse listSorted(
            ReservationListQuery query, SortedListCursor resumeCursor,
            ReservationCreatedAtIndexService createdAtIndex) {
        String effectiveSortBy = query.sort().sortBy() != null
            ? query.sort().sortBy().toLowerCase()
            : (resumeCursor != null ? resumeCursor.getSortBy() : "created_at_ms");
        String effectiveSortDir = query.sort().sortDir() != null
            ? query.sort().sortDir().toLowerCase()
            : (resumeCursor != null ? resumeCursor.getSortDir() : "desc");
        String filterHash = query.filterHash();

        if (resumeCursor != null
                && (!effectiveSortBy.equalsIgnoreCase(resumeCursor.getSortBy())
                    || !effectiveSortDir.equalsIgnoreCase(resumeCursor.getSortDir())
                    || !filterHash.equals(resumeCursor.getFilterHash()))) {
            throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                "cursor is not valid for the requested sort_by / sort_dir / filters; reset cursor when any of these change",
                400);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            if ("created_at_ms".equals(effectiveSortBy) && createdAtIndex != null) {
                ReservationListResponse indexed = tryIndexedRead(
                    jedis, query, resumeCursor, effectiveSortDir, filterHash, createdAtIndex);
                if (indexed != null) return indexed;
            }
            return listSortedByScan(jedis, query, resumeCursor, effectiveSortBy,
                effectiveSortDir, filterHash, createdAtIndex);
        }
    }

    private ReservationListResponse tryIndexedRead(
            Jedis jedis, ReservationListQuery query, SortedListCursor resumeCursor,
            String sortDir, String filterHash, ReservationCreatedAtIndexService createdAtIndex) {
        if (!createdAtIndex.isEnabled()) {
            metrics.recordReservationIndexRead("SCAN_DISABLED");
            return null;
        }
        if (!createdAtIndex.isReady(jedis, query.tenant())) {
            metrics.recordReservationIndexRead("SCAN_NOT_READY");
            return null;
        }
        try {
            ReservationListResponse indexed = listByCreatedAtIndex(
                jedis, query, resumeCursor, sortDir, filterHash, createdAtIndex);
            if (indexed != null) {
                metrics.recordReservationIndexRead("INDEX");
                return indexed;
            }
            metrics.recordReservationIndexRead("SCAN_DRIFT");
            LOG.warn("Reservation created-at index changed during read; using full scan: tenant={}",
                LogSanitizer.sanitize(query.tenant()));
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            metrics.recordReservationIndexRead("SCAN_ERROR");
            LOG.warn("Reservation created-at index read failed; using full scan: tenant={}",
                LogSanitizer.sanitize(query.tenant()), e);
            try {
                createdAtIndex.invalidate(jedis, query.tenant());
            } catch (Exception invalidationError) {
                e.addSuppressed(invalidationError);
            }
        }
        return null;
    }

    private ReservationListResponse listSortedByScan(
            Jedis jedis, ReservationListQuery query, SortedListCursor resumeCursor,
            String sortBy, String sortDir, String filterHash,
            ReservationCreatedAtIndexService createdAtIndex) {
        ScanParams params = new ScanParams().match("reservation:res_*").count(500);
        List<ReservationSummary> matching = new ArrayList<>();
        int tenantRowsSeen = 0;
        List<String> projection = ReservationHashMapper.projection(query.include(), false);
        String[] projectionFields = projection.toArray(String[]::new);

        String scanCursor = "0";
        do {
            ScanResult<String> scan = jedis.scan(scanCursor, params);
            List<String> keys = scan.getResult();
            if (!keys.isEmpty()) {
                Map<String, Response<List<String>>> responses = new HashMap<>();
                try (Pipeline pipeline = jedis.pipelined()) {
                    for (String key : keys) {
                        responses.put(key, pipeline.hmget(key, projectionFields));
                    }
                    pipeline.sync();
                }

                for (String key : keys) {
                    try {
                        Map<String, String> fields = HashProjections.mapHashFields(
                            projection, responses.get(key).get());
                        if (fields.isEmpty()) continue;
                        if (query.tenant().equals(fields.get("tenant"))) tenantRowsSeen++;
                        ReservationSummary summary = hashMapper.matchingSummary(fields, query);
                        if (summary != null) matching.add(summary);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse reservation: {}",
                            LogSanitizer.sanitize(key), e);
                    }
                }
            }
            scanCursor = scan.getCursor();
        } while (!"0".equals(scanCursor));

        if (matching.size() >= SORTED_HYDRATE_WARN_THRESHOLD) {
            LOG.warn("listReservationsSorted hydrated {} rows for tenant={} sort_by={} sort_dir={}; narrow filters or add sorted indices before this grows further",
                matching.size(), LogSanitizer.sanitize(query.tenant()), sortBy, sortDir);
        }

        matching.sort(ReservationComparators.of(sortBy, sortDir));
        LOG.debug("listReservationsSorted: tenant={} matched={} sort_by={} sort_dir={}",
            query.tenant(), matching.size(), sortBy, sortDir);

        int start = resumeCursor == null
            ? 0 : findSliceStart(matching, sortBy, sortDir, resumeCursor);
        int end = Math.min(start + query.limit(), matching.size());
        List<ReservationSummary> page = new ArrayList<>(matching.subList(start, end));

        String nextCursor = null;
        if (end < matching.size() && !page.isEmpty()) {
            ReservationSummary last = page.get(page.size() - 1);
            nextCursor = new SortedListCursor(
                1, sortBy, sortDir, filterHash,
                ReservationComparators.extractSortValue(last, sortBy),
                last.getReservationId()).encode();
        }

        if ("created_at_ms".equals(sortBy) && createdAtIndex != null
                && createdAtIndex.isEnabled()) {
            if (tenantRowsSeen == 0) {
                try {
                    createdAtIndex.publishEmptyReadiness(jedis, query.tenant());
                } catch (Exception e) {
                    LOG.warn("Unable to publish empty reservation index readiness: tenant={}",
                        LogSanitizer.sanitize(query.tenant()), e);
                }
            } else {
                createdAtIndex.requestRepair();
            }
        }

        return ReservationListResponse.builder()
            .reservations(page)
            .hasMore(nextCursor != null)
            .nextCursor(nextCursor)
            .build();
    }

    /**
     * Hydrates a bounded candidate window from the created-at index. A null
     * result means index trust was lost and the caller must use full SCAN.
     */
    private ReservationListResponse listByCreatedAtIndex(
            Jedis jedis, ReservationListQuery query, SortedListCursor resumeCursor,
            String sortDir, String filterHash,
            ReservationCreatedAtIndexService createdAtIndex) {
        boolean descending = "desc".equalsIgnoreCase(sortDir);
        String lowerBound = query.createdAt().from() != null
            ? String.valueOf(query.createdAt().from()) : "-inf";
        String upperBound = query.createdAt().to() != null
            ? String.valueOf(query.createdAt().to()) : "+inf";
        Long resumeScore = null;
        String resumeId = null;
        if (resumeCursor != null) {
            try {
                resumeScore = Long.parseLong(resumeCursor.getLastSortValue());
            } catch (Exception e) {
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                    "cursor contains an invalid created_at_ms boundary", 400);
            }
            resumeId = resumeCursor.getLastReservationId();
            if (descending) {
                if (query.createdAt().from() != null
                        && resumeScore < query.createdAt().from()) {
                    return emptyPage();
                }
                if (query.createdAt().to() == null || resumeScore < query.createdAt().to()) {
                    upperBound = String.valueOf(resumeScore);
                }
            } else {
                if (query.createdAt().to() != null && resumeScore > query.createdAt().to()) {
                    return emptyPage();
                }
                if (query.createdAt().from() == null || resumeScore > query.createdAt().from()) {
                    lowerBound = String.valueOf(resumeScore);
                }
            }
        }

        List<String> projection = ReservationHashMapper.projection(query.include(), false);
        String[] projectionFields = projection.toArray(String[]::new);
        List<ReservationSummary> matching = new ArrayList<>(query.limit() + 1);
        int candidatesHydrated = 0;
        int candidateBatchSize = query.hasPostIndexFilters()
            ? SORTED_INDEX_BATCH_SIZE
            : Math.max(1, (int) Math.min(SORTED_INDEX_BATCH_SIZE, (long) query.limit() + 1L));

        Long iterationScore = resumeScore;
        String iterationId = resumeId;
        while (matching.size() <= query.limit()) {
            List<ReservationCreatedAtIndexService.IndexCandidate> candidates =
                createdAtIndex.readPage(jedis, query.tenant(),
                    descending ? "desc" : "asc", lowerBound, upperBound,
                    iterationScore, iterationId, candidateBatchSize);
            if (candidates.isEmpty()) break;

            Map<String, Response<List<String>>> responses = new LinkedHashMap<>();
            try (Pipeline pipeline = jedis.pipelined()) {
                for (ReservationCreatedAtIndexService.IndexCandidate candidate : candidates) {
                    responses.put(candidate.reservationId(), pipeline.hmget(
                        "reservation:res_" + candidate.reservationId(), projectionFields));
                }
                pipeline.sync();
            }

            List<String> stale = new ArrayList<>();
            for (ReservationCreatedAtIndexService.IndexCandidate candidate : candidates) {
                String reservationId = candidate.reservationId();
                double rawScore = candidate.score();
                long score = (long) rawScore;
                if ((double) score != rawScore
                        || !ReservationCreatedAtIndexService.isExactRedisScore(score)) {
                    createdAtIndex.invalidate(jedis, query.tenant());
                    return null;
                }
                candidatesHydrated++;
                try {
                    Map<String, String> fields = HashProjections.mapHashFields(
                        projection, responses.get(reservationId).get());
                    if (fields.isEmpty()) {
                        if (jedis.exists("reservation:res_" + reservationId)) {
                            createdAtIndex.invalidate(jedis, query.tenant());
                            return null;
                        }
                        stale.add(reservationId);
                        continue;
                    }
                    String rowTenant = fields.get("tenant");
                    if (rowTenant == null) {
                        createdAtIndex.invalidate(jedis, query.tenant());
                        return null;
                    }
                    if (!query.tenant().equals(rowTenant)) {
                        stale.add(reservationId);
                        continue;
                    }
                    if (!reservationId.equals(fields.get("reservation_id"))) {
                        createdAtIndex.invalidate(jedis, query.tenant());
                        return null;
                    }
                    long rowScore;
                    try {
                        rowScore = Long.parseLong(fields.get("created_at"));
                    } catch (NumberFormatException e) {
                        createdAtIndex.invalidate(jedis, query.tenant());
                        return null;
                    }
                    if (rowScore != score) {
                        createdAtIndex.invalidate(jedis, query.tenant());
                        return null;
                    }
                    ReservationSummary summary = hashMapper.matchingSummary(fields, query);
                    if (summary != null) {
                        matching.add(summary);
                        if (matching.size() > query.limit()) break;
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse indexed reservation: reservation_id={}",
                        LogSanitizer.sanitize(reservationId), e);
                }
            }
            if (!stale.isEmpty()) {
                createdAtIndex.removeStaleMembers(jedis, query.tenant(), stale);
            }
            if (matching.size() > query.limit()) break;

            ReservationCreatedAtIndexService.IndexCandidate last =
                candidates.get(candidates.size() - 1);
            iterationScore = (long) last.score();
            iterationId = last.reservationId();
            if (descending) upperBound = String.valueOf(iterationScore);
            else lowerBound = String.valueOf(iterationScore);
            if (candidates.size() < candidateBatchSize) break;
        }

        if (!createdAtIndex.isReady(jedis, query.tenant())) return null;

        boolean hasMore = matching.size() > query.limit();
        List<ReservationSummary> page = hasMore
            ? new ArrayList<>(matching.subList(0, query.limit()))
            : new ArrayList<>(matching);
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ReservationSummary last = page.get(page.size() - 1);
            nextCursor = new SortedListCursor(
                1, "created_at_ms", sortDir, filterHash,
                ReservationComparators.extractSortValue(last, "created_at_ms"),
                last.getReservationId()).encode();
        }
        LOG.debug("listReservationsSorted indexed: tenant={} candidates={} matches={} sort_dir={}",
            query.tenant(), candidatesHydrated, matching.size(), sortDir);
        return ReservationListResponse.builder()
            .reservations(page).hasMore(hasMore).nextCursor(nextCursor).build();
    }

    private static ReservationListResponse emptyPage() {
        return ReservationListResponse.builder()
            .reservations(Collections.emptyList()).hasMore(false).nextCursor(null).build();
    }

    private static int findSliceStart(List<ReservationSummary> sorted, String sortBy,
                                      String sortDir, SortedListCursor cursor) {
        for (int i = 0; i < sorted.size(); i++) {
            ReservationSummary row = sorted.get(i);
            int keyComparison = compareAtBoundary(
                row, sortBy, sortDir, cursor.getLastSortValue());
            if (keyComparison > 0) return i;
            if (keyComparison == 0) {
                String id = row.getReservationId() == null ? "" : row.getReservationId();
                String lastId = cursor.getLastReservationId() == null
                    ? "" : cursor.getLastReservationId();
                if (id.compareTo(lastId) > 0) return i;
            }
        }
        return sorted.size();
    }

    private static int compareAtBoundary(ReservationSummary row, String sortBy,
                                         String sortDir, String lastSortValue) {
        String rowValue = ReservationComparators.extractSortValue(row, sortBy);
        boolean numeric = "reserved".equalsIgnoreCase(sortBy)
            || "created_at_ms".equalsIgnoreCase(sortBy)
            || "expires_at_ms".equalsIgnoreCase(sortBy);
        int raw;
        if (numeric) {
            long rowNumber = rowValue.isEmpty() ? Long.MIN_VALUE : Long.parseLong(rowValue);
            long lastNumber = lastSortValue == null || lastSortValue.isEmpty()
                ? Long.MIN_VALUE : Long.parseLong(lastSortValue);
            raw = Long.compare(rowNumber, lastNumber);
        } else {
            String left = rowValue == null ? "" : rowValue;
            String right = lastSortValue == null ? "" : lastSortValue;
            raw = left.compareTo(right);
        }
        return "desc".equalsIgnoreCase(sortDir) ? -raw : raw;
    }
}
