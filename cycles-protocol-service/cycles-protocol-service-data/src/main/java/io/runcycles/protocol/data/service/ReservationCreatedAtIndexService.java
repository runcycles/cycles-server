package io.runcycles.protocol.data.service;

import io.runcycles.protocol.data.maintenance.MaintenanceJob;
import io.runcycles.protocol.data.maintenance.RedisMaintenanceRunner;
import io.runcycles.protocol.data.util.HashProjections;
import io.runcycles.protocol.data.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds, validates, and repairs the optional per-tenant reservation
 * {@code created_at_ms} index.
 *
 * <p>Reservation hashes remain authoritative. A reader may use the ZSET only
 * after a complete backfill publishes READY metadata whose expected member
 * count still equals {@code ZCARD}. Any uncertainty selects the existing full
 * SCAN path; this service never trades completeness for latency.
 */
@Service
public class ReservationCreatedAtIndexService {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationCreatedAtIndexService.class);

    static final String INDEX_PREFIX = "reservation:idx:";
    static final String INDEX_SUFFIX = ":created_at_ms";
    static final String META_PREFIX = "reservation:idxmeta:";
    private static final String RESERVATION_PREFIX = "reservation:res_";
    private static final long MAX_EXACT_REDIS_INTEGER = 9_007_199_254_740_992L;
    private static final int SCAN_BATCH_SIZE = 500;
    private static final List<String> INDEX_ROW_FIELDS =
        List.of("reservation_id", "tenant", "created_at");
    private static final String[] INDEX_ROW_FIELD_ARRAY =
        INDEX_ROW_FIELDS.toArray(String[]::new);

    @Autowired private JedisPool jedisPool;
    @Autowired private LuaScriptRegistry luaScripts;
    @Autowired @Qualifier("reservationCreatedAtIndexLuaScript")
    private String indexScript;
    @Autowired private RedisMaintenanceRunner maintenanceRunner;

    @Value("${cycles.reservation-index.created-at.enabled:false}")
    private boolean enabled;

    @Value("${cycles.reservation-index.created-at.failure-backoff-ms:3600000}")
    private long failureBackoffMs;

    private final AtomicBoolean repairRequested = new AtomicBoolean(true);
    private final AtomicLong nextRepairAtMs = new AtomicLong(0L);

    public boolean isEnabled() {
        return enabled;
    }

    public static String indexKey(String tenant) {
        return INDEX_PREFIX + tenant + INDEX_SUFFIX;
    }

    public static String metadataKey(String tenant) {
        return META_PREFIX + tenant + INDEX_SUFFIX;
    }

    /** Atomic readiness and cardinality validation on the caller's connection. */
    public boolean isReady(Jedis jedis, String tenant) {
        if (!enabled || tenant == null || tenant.isBlank()) {
            return false;
        }
        try {
            long result = ((Number) luaScripts.eval(
                jedis, "reservationCreatedAtIndex", indexScript, "validate", tenant)).longValue();
            if (result == 1L) {
                return true;
            }
            // 2 means neither index nor metadata exists. The authoritative
            // fallback scan will distinguish an empty tenant (publish READY/0)
            // from missing state that needs reconciliation.
            if (result == 2L) {
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Reservation created-at index validation failed; using full scan: tenant={}",
                LogSanitizer.sanitize(tenant), e);
        }
        requestRepair();
        return false;
    }

    public void requestRepair() {
        if (enabled) {
            repairRequested.set(true);
        }
    }

    public void invalidate(Jedis jedis, String tenant) {
        if (tenant == null || tenant.isBlank()) return;
        try {
            luaScripts.eval(jedis, "reservationCreatedAtIndex", indexScript, "invalidate", tenant);
        } finally {
            requestRepair();
        }
    }

    /**
     * Publishes READY after a complete authoritative scan found no rows for the
     * tenant. If a concurrent new writer already created the ZSET, finalize uses
     * its live ZCARD, so the transition remains complete.
     */
    public boolean publishEmptyReadiness(Jedis jedis, String tenant) {
        if (!enabled || tenant == null || tenant.isBlank()) return false;
        long result = ((Number) luaScripts.eval(jedis,
            "reservationCreatedAtIndex", indexScript,
            "finalize", tenant, String.valueOf(Instant.now().toEpochMilli()))).longValue();
        if (result != 1L) requestRepair();
        return result == 1L;
    }

    /**
     * Lazy read-path cleanup. Count repair is atomic with member removal, so a
     * concurrent reserve is included whether it executes before or after this script.
     */
    public void removeStaleMembers(Jedis jedis, String tenant, List<String> reservationIds) {
        if (!enabled || reservationIds == null || reservationIds.isEmpty()) return;
        List<String> args = new ArrayList<>(reservationIds.size() + 2);
        args.add("remove");
        args.add(tenant);
        args.addAll(reservationIds);
        long result = ((Number) luaScripts.eval(
            jedis, "reservationCreatedAtIndex", indexScript, args.toArray(String[]::new))).longValue();
        if (result < 0) {
            requestRepair();
        }
    }

    /**
     * Fetches one bounded candidate batch in timestamp-direction / id-ascending
     * order. Equal-score grouping happens inside Redis so the common unique-score
     * path costs one EVALSHA rather than two client round trips per candidate.
     */
    public List<IndexCandidate> readPage(Jedis jedis, String tenant, String direction,
                                         String lowerBound, String upperBound,
                                         Long cursorScore, String cursorId, int limit) {
        Object raw = luaScripts.eval(jedis, "reservationCreatedAtIndex", indexScript,
            "page", tenant, direction, lowerBound, upperBound,
            cursorScore == null ? "" : String.valueOf(cursorScore),
            cursorId == null ? "" : cursorId, String.valueOf(limit));
        if (!(raw instanceof List<?> values) || values.size() % 2 != 0) {
            throw new IllegalStateException("Invalid reservation created-at index page response");
        }
        List<IndexCandidate> result = new ArrayList<>(values.size() / 2);
        for (int i = 0; i < values.size(); i += 2) {
            result.add(new IndexCandidate(
                String.valueOf(values.get(i)),
                Double.parseDouble(String.valueOf(values.get(i + 1)))));
        }
        return result;
    }

    @Scheduled(
        fixedDelayString = "${cycles.reservation-index.created-at.repair-interval-ms:300000}",
        initialDelayString = "${cycles.reservation-index.created-at.initial-delay-ms:5000}")
    public void scheduledRepairIfRequested() {
        maintenanceRunner.runIf(MaintenanceJob.CREATED_AT_REPAIR,
            enabled, this::repairIfRequestedOnce);
    }

    public void repairIfRequested() {
        try {
            repairIfRequestedOnce();
        } catch (Exception e) {
            LOG.error("Reservation created-at index repair failed; indexed reads remain disabled", e);
        }
    }

    private void repairIfRequestedOnce() {
        if (!enabled || !repairRequested.get()) return;
        long now = Instant.now().toEpochMilli();
        if (now < nextRepairAtMs.get()
                || !repairRequested.compareAndSet(true, false)) return;
        try {
            ReconcileResult result = reconcileNow();
            if (result.tenantsFailed() > 0) {
                nextRepairAtMs.set(saturatingAdd(
                    Instant.now().toEpochMilli(), Math.max(0L, failureBackoffMs)));
                repairRequested.set(true);
            } else {
                nextRepairAtMs.set(0L);
            }
        } catch (Exception e) {
            nextRepairAtMs.set(0L);
            repairRequested.set(true);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e);
        }
    }

    /**
     * Restartable global backfill. Valid rows are added idempotently to live
     * per-tenant ZSETs. READY is published only after the full SCAN completes and
     * every row associated with that tenant was representable and every ZADD succeeded.
     */
    public synchronized ReconcileResult reconcileNow() {
        if (!enabled) return new ReconcileResult(0, 0, 0);

        Set<String> tenantsSeen = new HashSet<>();
        Set<String> failedTenants = new HashSet<>();
        Set<String> preparedTenants = new HashSet<>();
        int keysScanned = 0;

        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match(RESERVATION_PREFIX + "*").count(SCAN_BATCH_SIZE);
            String cursor = "0";
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                List<String> keys = scan.getResult();
                keysScanned += keys.size();
                if (!keys.isEmpty()) {
                    backfillBatch(jedis, keys, tenantsSeen, failedTenants, preparedTenants);
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));

            int ready = 0;
            long now = Instant.now().toEpochMilli();
            for (String tenant : tenantsSeen) {
                if (failedTenants.contains(tenant)) {
                    luaScripts.eval(jedis, "reservationCreatedAtIndex", indexScript,
                        "invalidate", tenant);
                    continue;
                }
                long finalized = ((Number) luaScripts.eval(
                    jedis, "reservationCreatedAtIndex", indexScript,
                    "finalize", tenant, String.valueOf(now))).longValue();
                if (finalized == 1L) {
                    ready++;
                } else {
                    failedTenants.add(tenant);
                }
            }

            LOG.info("Reservation created-at index reconciliation completed: keys_scanned={} tenants_ready={} tenants_failed={}",
                keysScanned, ready, failedTenants.size());
            return new ReconcileResult(keysScanned, ready, failedTenants.size());
        }
    }

    private void backfillBatch(Jedis jedis, List<String> keys, Set<String> tenantsSeen,
                               Set<String> failedTenants, Set<String> preparedTenants) {
        Map<String, Response<List<String>>> projections = new LinkedHashMap<>();
        try (Pipeline pipeline = jedis.pipelined()) {
            for (String key : keys) {
                projections.put(key, pipeline.hmget(key, INDEX_ROW_FIELD_ARRAY));
            }
            pipeline.sync();
        }

        List<BackfillRow> rows = new ArrayList<>();
        for (Map.Entry<String, Response<List<String>>> entry : projections.entrySet()) {
            String key = entry.getKey();
            try {
                Map<String, String> fields = HashProjections.mapHashFields(
                    INDEX_ROW_FIELDS, entry.getValue().get());
                String reservationId = fields.get("reservation_id");
                String tenant = fields.get("tenant");
                String createdAtRaw = fields.get("created_at");
                if (tenant == null || tenant.isBlank()) {
                    LOG.warn("Skipping reservation with no tenant during created-at backfill: key={}",
                        LogSanitizer.sanitize(key));
                    continue;
                }
                tenantsSeen.add(tenant);
                String keyReservationId = key.substring(RESERVATION_PREFIX.length());
                long createdAt = Long.parseLong(createdAtRaw);
                if (reservationId == null || reservationId.isBlank()
                        || !reservationId.equals(keyReservationId)
                        || !isExactRedisScore(createdAt)) {
                    failedTenants.add(tenant);
                    continue;
                }
                if (preparedTenants.add(tenant)) {
                    // Validation also deletes an exclusively-owned wrong-type
                    // index key so this reconciliation can rebuild it.
                    luaScripts.eval(jedis, "reservationCreatedAtIndex", indexScript,
                        "validate", tenant);
                }
                rows.add(new BackfillRow(tenant, reservationId, createdAt));
            } catch (Exception e) {
                String tenant = safeTenant(entry.getValue());
                if (tenant != null && !tenant.isBlank()) {
                    tenantsSeen.add(tenant);
                    failedTenants.add(tenant);
                }
                LOG.warn("Skipping malformed reservation during created-at backfill: key={} error={}",
                    LogSanitizer.sanitize(key),
                    LogSanitizer.sanitize(String.valueOf(e.getMessage())));
            }
        }

        Map<BackfillRow, Response<Long>> writes = new HashMap<>();
        try (Pipeline pipeline = jedis.pipelined()) {
            for (BackfillRow row : rows) {
                writes.put(row, pipeline.zadd(indexKey(row.tenant()), row.createdAt(), row.reservationId()));
            }
            pipeline.sync();
        } catch (Exception e) {
            rows.forEach(row -> failedTenants.add(row.tenant()));
            LOG.warn("Reservation created-at backfill batch failed", e);
            return;
        }
        for (Map.Entry<BackfillRow, Response<Long>> write : writes.entrySet()) {
            try {
                write.getValue().get();
            } catch (Exception e) {
                failedTenants.add(write.getKey().tenant());
                LOG.warn("Failed to index reservation during created-at backfill: tenant={} reservation_id={}",
                    LogSanitizer.sanitize(write.getKey().tenant()),
                    LogSanitizer.sanitize(write.getKey().reservationId()), e);
            }
        }
    }

    @Scheduled(cron = "${cycles.reservation-index.created-at.sweep-cron:0 45 3 * * *}")
    public void scheduledSweepStaleMembers() {
        maintenanceRunner.runIf(MaintenanceJob.CREATED_AT_SWEEP,
            enabled, this::sweepStaleMembersOnce);
    }

    public void sweepStaleMembers() {
        try {
            sweepStaleMembersOnce();
        } catch (Exception e) {
            LOG.error("Reservation created-at index sweep failed; next run will retry", e);
        }
    }

    private synchronized void sweepStaleMembersOnce() {
        if (!enabled) return;
        int removed = 0;
        int corrected = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams indexParams = new ScanParams().match(INDEX_PREFIX + "*" + INDEX_SUFFIX).count(100);
            String cursor = "0";
            do {
                ScanResult<String> scan = jedis.scan(cursor, indexParams);
                for (String key : scan.getResult()) {
                    try {
                        SweepResult result = sweepIndex(jedis, key);
                        removed += result.removed();
                        corrected += result.corrected();
                    } catch (Exception e) {
                        String tenant = tenantFromIndexKey(key);
                        if (tenant != null) {
                            try {
                                invalidate(jedis, tenant);
                            } catch (Exception invalidationError) {
                                e.addSuppressed(invalidationError);
                            }
                        }
                        requestRepair();
                        LOG.warn("Skipping invalid reservation created-at index during sweep: key={}",
                            LogSanitizer.sanitize(key), e);
                    }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));
            LOG.info("Reservation created-at index sweep completed: removed={} corrected_scores={}",
                removed, corrected);
        } catch (Exception e) {
            requestRepair();
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e);
        }
    }

    private SweepResult sweepIndex(Jedis jedis, String indexKey) {
        String tenant = tenantFromIndexKey(indexKey);
        if (tenant == null) return new SweepResult(0, 0);
        long validation = ((Number) luaScripts.eval(
            jedis, "reservationCreatedAtIndex", indexScript, "validate", tenant)).longValue();
        if (validation != 1L) {
            requestRepair();
        }
        int removed = 0;
        int corrected = 0;
        String cursor = "0";
        ScanParams params = new ScanParams().count(SCAN_BATCH_SIZE);
        do {
            ScanResult<Tuple> scan = jedis.zscan(indexKey, cursor, params);
            List<Tuple> tuples = scan.getResult();
            Map<Tuple, Response<List<String>>> projections = new LinkedHashMap<>();
            try (Pipeline pipeline = jedis.pipelined()) {
                for (Tuple tuple : tuples) {
                    projections.put(tuple, pipeline.hmget(
                        RESERVATION_PREFIX + tuple.getElement(), INDEX_ROW_FIELD_ARRAY));
                }
                pipeline.sync();
            }

            List<String> stale = new ArrayList<>();
            for (Map.Entry<Tuple, Response<List<String>>> entry : projections.entrySet()) {
                Tuple tuple = entry.getKey();
                try {
                    Map<String, String> fields = HashProjections.mapHashFields(
                        INDEX_ROW_FIELDS, entry.getValue().get());
                    if (fields.isEmpty()) {
                        if (jedis.exists(RESERVATION_PREFIX + tuple.getElement())) {
                            invalidate(jedis, tenant);
                            return new SweepResult(removed, corrected);
                        } else {
                            stale.add(tuple.getElement());
                            continue;
                        }
                    }
                    String reservationId = fields.get("reservation_id");
                    String rowTenant = fields.get("tenant");
                    String createdAtRaw = fields.get("created_at");
                    if (rowTenant == null) {
                        invalidate(jedis, tenant);
                        return new SweepResult(removed, corrected);
                    }
                    if (!tenant.equals(rowTenant)) {
                        stale.add(tuple.getElement());
                        continue;
                    }
                    if (!tuple.getElement().equals(reservationId)) {
                        invalidate(jedis, tenant);
                        return new SweepResult(removed, corrected);
                    }
                    long createdAt = Long.parseLong(createdAtRaw);
                    if (!isExactRedisScore(createdAt)) {
                        invalidate(jedis, tenant);
                        return new SweepResult(removed, corrected);
                    }
                    if (tuple.getScore() != (double) createdAt) {
                        jedis.zadd(indexKey, createdAt, tuple.getElement());
                        corrected++;
                    }
                } catch (Exception e) {
                    invalidate(jedis, tenant);
                    LOG.warn("Invalid reservation row found during created-at index sweep: tenant={} reservation_id={}",
                        LogSanitizer.sanitize(tenant),
                        LogSanitizer.sanitize(tuple.getElement()), e);
                    return new SweepResult(removed, corrected);
                }
            }
            if (!stale.isEmpty()) {
                removeStaleMembers(jedis, tenant, stale);
                removed += stale.size();
            }
            cursor = scan.getCursor();
        } while (!"0".equals(cursor));
        return new SweepResult(removed, corrected);
    }

    private static String tenantFromIndexKey(String key) {
        if (key == null || !key.startsWith(INDEX_PREFIX) || !key.endsWith(INDEX_SUFFIX)) return null;
        String tenant = key.substring(INDEX_PREFIX.length(), key.length() - INDEX_SUFFIX.length());
        return tenant.isBlank() ? null : tenant;
    }

    private static String safeTenant(Response<List<String>> response) {
        try {
            return HashProjections.mapHashFields(
                INDEX_ROW_FIELDS, response.get()).get("tenant");
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isExactRedisScore(long value) {
        return value >= -MAX_EXACT_REDIS_INTEGER && value <= MAX_EXACT_REDIS_INTEGER;
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private record BackfillRow(String tenant, String reservationId, long createdAt) {}
    private record SweepResult(int removed, int corrected) {}

    public record IndexCandidate(String reservationId, double score) {}
    public record ReconcileResult(int keysScanned, int tenantsReady, int tenantsFailed) {}
}
