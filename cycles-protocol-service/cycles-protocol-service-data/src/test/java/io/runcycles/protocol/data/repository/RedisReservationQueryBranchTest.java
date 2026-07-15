package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.support.ReservationListQuery;
import io.runcycles.protocol.data.repository.support.SortedListCursor;
import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import io.runcycles.protocol.model.Amount;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ReservationListResponse;
import io.runcycles.protocol.model.ReservationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisReservationQueryBranchTest {

    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);
    private final Pipeline pipeline = mock(Pipeline.class);
    private final ReservationHashMapper hashMapper = mock(ReservationHashMapper.class);
    private RedisReservationQueryRepository repository;

    @BeforeEach
    void setUp() {
        when(pool.getResource()).thenReturn(jedis);
        repository = new RedisReservationQueryRepository(pool, mock(CyclesMetrics.class), hashMapper);
    }

    @Test
    void legacyScanTraversesMultipleBatchesAndSkipsDeletedHashes() {
        @SuppressWarnings("unchecked")
        Response<List<String>> response = mock(Response.class);
        when(jedis.scan(eq("0"), any(ScanParams.class)))
            .thenReturn(new ScanResult<>("7".getBytes(), List.of("reservation:res_deleted")));
        when(jedis.scan(eq("7"), any(ScanParams.class)))
            .thenReturn(new ScanResult<>("0".getBytes(), List.of()));
        when(jedis.pipelined()).thenReturn(pipeline);
        when(pipeline.hmget(eq("reservation:res_deleted"), any(String[].class))).thenReturn(response);
        when(response.get()).thenReturn(Collections.nCopies(15, null));

        ReservationListResponse page = repository.list(
            ReservationListQuery.builder("tenant", 10).build(), null);

        assertThat(page.getReservations()).isEmpty();
        verify(jedis).scan(eq("7"), any(ScanParams.class));
        verifyNoInteractions(hashMapper);
    }

    @Test
    void invalidCursorChecksCoverDecodedAndOpaqueSortedForms() {
        String invalidBoundary = new SortedListCursor(1, "created_at_ms", "desc",
            "hash", null, "r1").encode();
        assertThatThrownBy(() -> repository.list(ReservationListQuery.builder("tenant", 10)
            .sort("created_at_ms", "desc").cursor(invalidBoundary).build(), null))
            .isInstanceOf(CyclesProtocolException.class);
        assertThatThrownBy(() -> repository.list(ReservationListQuery.builder("tenant", 10)
            .sort("status", "asc").cursor("opaque-not-sorted").build(), null))
            .isInstanceOf(CyclesProtocolException.class);
    }

    @Test
    void sortedDefaultsAndResumeCursorSupplyMissingSortOptions() throws Exception {
        when(jedis.scan(anyString(), any(ScanParams.class)))
            .thenReturn(new ScanResult<>("0".getBytes(), List.of()));
        Method listSorted = privateMethod("listSorted", ReservationListQuery.class,
            SortedListCursor.class, ReservationCreatedAtIndexService.class);

        ReservationListResponse defaults = (ReservationListResponse) listSorted.invoke(repository,
            ReservationListQuery.builder("tenant", 10).build(), null, null);
        assertThat(defaults.getReservations()).isEmpty();

        ReservationListQuery query = ReservationListQuery.builder("tenant", 10).build();
        SortedListCursor resume = new SortedListCursor(1, "status", "asc", query.filterHash(),
            "ACTIVE", "r1");
        ReservationListResponse resumed = (ReservationListResponse) listSorted.invoke(
            repository, query, resume, null);
        assertThat(resumed.getReservations()).isEmpty();
    }

    @Test
    void resumeValidationEvaluatesSortDirectionAndFilterMismatches() throws Exception {
        Method listSorted = privateMethod("listSorted", ReservationListQuery.class,
            SortedListCursor.class, ReservationCreatedAtIndexService.class);
        ReservationListQuery query = ReservationListQuery.builder("tenant", 10)
            .sort("status", "asc").build();

        assertInvocationCause(listSorted, query,
            new SortedListCursor(1, "tenant", "asc", query.filterHash(), "tenant", "r1"));
        assertInvocationCause(listSorted, query,
            new SortedListCursor(1, "status", "desc", query.filterHash(), "ACTIVE", "r1"));
        assertInvocationCause(listSorted, query,
            new SortedListCursor(1, "status", "asc", "different", "ACTIVE", "r1"));
    }

    @Test
    void createdAtResumeBoundsCoverBothDirectionsAndOpenWindows() throws Exception {
        Method indexed = privateMethod("listByCreatedAtIndex", Jedis.class,
            ReservationListQuery.class, SortedListCursor.class, String.class, String.class,
            ReservationCreatedAtIndexService.class);
        ReservationCreatedAtIndexService index = mock(ReservationCreatedAtIndexService.class);
        when(index.readPage(any(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyInt())).thenReturn(List.of());
        when(index.isReady(jedis, "tenant")).thenReturn(true);

        ReservationListQuery descendingWindow = ReservationListQuery.builder("tenant", 10)
            .createdAt(100L, 200L).build();
        assertThat(((ReservationListResponse) indexed.invoke(repository, jedis, descendingWindow,
            cursor(descendingWindow, "desc", 99L), "desc", descendingWindow.filterHash(), index))
            .getReservations()).isEmpty();
        assertThat(((ReservationListResponse) indexed.invoke(repository, jedis, descendingWindow,
            cursor(descendingWindow, "desc", 200L), "desc", descendingWindow.filterHash(), index))
            .getReservations()).isEmpty();

        ReservationListQuery descendingOpen = ReservationListQuery.builder("tenant", 10)
            .createdAt(null, null).build();
        indexed.invoke(repository, jedis, descendingOpen, cursor(descendingOpen, "desc", 150L),
            "desc", descendingOpen.filterHash(), index);

        ReservationListQuery ascendingWindow = ReservationListQuery.builder("tenant", 10)
            .createdAt(100L, 200L).build();
        assertThat(((ReservationListResponse) indexed.invoke(repository, jedis, ascendingWindow,
            cursor(ascendingWindow, "asc", 201L), "asc", ascendingWindow.filterHash(), index))
            .getReservations()).isEmpty();
        indexed.invoke(repository, jedis, ascendingWindow, cursor(ascendingWindow, "asc", 100L),
            "asc", ascendingWindow.filterHash(), index);
        ReservationListQuery ascendingOpen = ReservationListQuery.builder("tenant", 10).build();
        indexed.invoke(repository, jedis, ascendingOpen, cursor(ascendingOpen, "asc", 150L),
            "asc", ascendingOpen.filterHash(), index);
    }

    @Test
    void boundaryComparisonHandlesNullIdsNumericEmptiesAndEveryNumericSortKey() throws Exception {
        Method compare = privateMethod("compareAtBoundary", ReservationSummary.class,
            String.class, String.class, String.class);
        ReservationSummary sparse = ReservationSummary.builder().reservationId(null).build();
        ReservationSummary numeric = ReservationSummary.builder().reservationId("r2")
            .reserved(new Amount(Enums.UnitEnum.TOKENS, 5L)).createdAtMs(10L).expiresAtMs(20L).build();

        assertThat((int) compare.invoke(null, sparse, "reserved", "asc", null)).isZero();
        assertThat((int) compare.invoke(null, numeric, "reserved", "desc", "4")).isNegative();
        assertThat((int) compare.invoke(null, numeric, "created_at_ms", "asc", "9")).isPositive();
        assertThat((int) compare.invoke(null, numeric, "expires_at_ms", "asc", "20")).isZero();
        assertThat((int) compare.invoke(null, sparse, "status", "asc", null)).isZero();
        assertThat((int) compare.invoke(null, sparse, "reserved", "asc", "")).isZero();

        Method slice = privateMethod("findSliceStart", List.class, String.class,
            String.class, SortedListCursor.class);
        SortedListCursor nullId = new SortedListCursor(1, "status", "asc", "hash", "", null);
        assertThat((int) slice.invoke(null, List.of(sparse, numeric), "status", "asc", nullId))
            .isEqualTo(1);
    }

    @Test
    void sortedListAcceptsNullAndBlankStartingCursors() {
        when(jedis.scan(anyString(), any(ScanParams.class)))
            .thenReturn(new ScanResult<>("0".getBytes(), List.of()));

        assertThat(repository.list(ReservationListQuery.builder("tenant", 10)
            .sort("status", "asc").build(), null).getReservations()).isEmpty();
        assertThat(repository.list(ReservationListQuery.builder("tenant", 10)
            .sort("status", "asc").cursor(" ").build(), null).getReservations()).isEmpty();
    }

    @Test
    void zeroLimitSortedScanReturnsAnEmptyPageWhenRowsMatch() throws Exception {
        ReservationListQuery query = ReservationListQuery.builder("tenant", 0)
            .sort("status", "asc").build();
        @SuppressWarnings("unchecked")
        Response<List<String>> row = mock(Response.class);
        when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(
            new ScanResult<>("0".getBytes(), List.of("reservation:res_r1")));
        when(jedis.pipelined()).thenReturn(pipeline);
        when(pipeline.hmget(eq("reservation:res_r1"), any(String[].class))).thenReturn(row);
        when(row.get()).thenReturn(project(query, Map.of("reservation_id", "r1")));
        when(hashMapper.matchingSummary(anyMap(), eq(query))).thenReturn(
            ReservationSummary.builder().reservationId("r1").build());

        ReservationListResponse response = repository.list(query, null);

        assertThat(response.getReservations()).isEmpty();
        assertThat(response.getHasMore()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    void zeroLimitIndexedReadReturnsAnEmptyPageWhenRowsMatch() throws Exception {
        ReservationListQuery query = ReservationListQuery.builder("tenant", 0)
            .sort("created_at_ms", "asc").build();
        @SuppressWarnings("unchecked")
        Response<List<String>> row = mock(Response.class);
        when(jedis.pipelined()).thenReturn(pipeline);
        when(pipeline.hmget(eq("reservation:res_r1"), any(String[].class))).thenReturn(row);
        when(row.get()).thenReturn(project(query, Map.of(
            "reservation_id", "r1", "tenant", "tenant", "created_at", "10")));
        when(hashMapper.matchingSummary(anyMap(), eq(query))).thenReturn(
            ReservationSummary.builder().reservationId("r1").createdAtMs(10L).build());
        ReservationCreatedAtIndexService index = mock(ReservationCreatedAtIndexService.class);
        when(index.isEnabled()).thenReturn(true);
        when(index.isReady(jedis, "tenant")).thenReturn(true);
        when(index.readPage(eq(jedis), eq("tenant"), eq("asc"), eq("-inf"), eq("+inf"),
            isNull(), isNull(), eq(1))).thenReturn(List.of(
                new ReservationCreatedAtIndexService.IndexCandidate("r1", 10)));

        ReservationListResponse response = repository.list(query, index);

        assertThat(response.getReservations()).isEmpty();
        assertThat(response.getHasMore()).isTrue();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    void indexedReadRejectsFractionalScoresExistingEmptyHashesAndMissingTenants() throws Exception {
        ReservationListQuery query = ReservationListQuery.builder("tenant", 1).build();
        @SuppressWarnings("unchecked")
        Response<List<String>> empty = mock(Response.class);
        when(empty.get()).thenReturn(Collections.nCopies(
            ReservationHashMapper.projection(query.include(), false).size(), null));
        when(jedis.pipelined()).thenReturn(pipeline);
        when(pipeline.hmget(anyString(), any(String[].class))).thenReturn(empty);

        ReservationCreatedAtIndexService fractional = mock(ReservationCreatedAtIndexService.class);
        when(fractional.readPage(any(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyInt())).thenReturn(
                List.of(new ReservationCreatedAtIndexService.IndexCandidate("fractional", 1.5)));
        assertThat(invokeIndexed(query, fractional)).isNull();
        verify(fractional).invalidate(jedis, "tenant");

        when(jedis.exists("reservation:res_existing")).thenReturn(true);
        ReservationCreatedAtIndexService existing = mock(ReservationCreatedAtIndexService.class);
        when(existing.readPage(any(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyInt())).thenReturn(
                List.of(new ReservationCreatedAtIndexService.IndexCandidate("existing", 2)));
        assertThat(invokeIndexed(query, existing)).isNull();
        verify(existing).invalidate(jedis, "tenant");

        Map<String, String> missingTenant = new HashMap<>();
        missingTenant.put("reservation_id", "missing-tenant");
        missingTenant.put("created_at", "3");
        @SuppressWarnings("unchecked")
        Response<List<String>> projected = mock(Response.class);
        when(projected.get()).thenReturn(project(query, missingTenant));
        when(pipeline.hmget(eq("reservation:res_missing-tenant"), any(String[].class)))
            .thenReturn(projected);
        ReservationCreatedAtIndexService tenantless = mock(ReservationCreatedAtIndexService.class);
        when(tenantless.readPage(any(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyInt())).thenReturn(
                List.of(new ReservationCreatedAtIndexService.IndexCandidate("missing-tenant", 3)));
        assertThat(invokeIndexed(query, tenantless)).isNull();
        verify(tenantless).invalidate(jedis, "tenant");
    }

    @Test
    void indexedReadRemovesDeletedCandidatesAndRequestsAnotherBatch() throws Exception {
        ReservationListQuery query = ReservationListQuery.builder("tenant", 1).build();
        @SuppressWarnings("unchecked")
        Response<List<String>> empty = mock(Response.class);
        when(empty.get()).thenReturn(Collections.nCopies(
            ReservationHashMapper.projection(query.include(), false).size(), null));
        when(jedis.pipelined()).thenReturn(pipeline);
        when(pipeline.hmget(anyString(), any(String[].class))).thenReturn(empty);
        when(jedis.exists(anyString())).thenReturn(false);
        ReservationCreatedAtIndexService index = mock(ReservationCreatedAtIndexService.class);
        when(index.readPage(any(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyInt())).thenReturn(List.of(
                new ReservationCreatedAtIndexService.IndexCandidate("deleted-1", 1),
                new ReservationCreatedAtIndexService.IndexCandidate("deleted-2", 2)), List.of());
        when(index.isReady(jedis, "tenant")).thenReturn(true);

        ReservationListResponse response = invokeIndexed(query, index);

        assertThat(response.getReservations()).isEmpty();
        verify(index, times(2)).readPage(any(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyInt());
        verify(index).removeStaleMembers(jedis, "tenant", List.of("deleted-1", "deleted-2"));
    }

    private ReservationListResponse invokeIndexed(ReservationListQuery query,
                                                   ReservationCreatedAtIndexService index)
            throws Exception {
        Method indexed = privateMethod("listByCreatedAtIndex", Jedis.class,
            ReservationListQuery.class, SortedListCursor.class, String.class, String.class,
            ReservationCreatedAtIndexService.class);
        return (ReservationListResponse) indexed.invoke(repository, jedis, query, null,
            "asc", query.filterHash(), index);
    }

    private static List<String> project(ReservationListQuery query, Map<String, String> fields) {
        return ReservationHashMapper.projection(query.include(), false).stream()
            .map(fields::get).toList();
    }

    private SortedListCursor cursor(ReservationListQuery query, String direction, long score) {
        return new SortedListCursor(1, "created_at_ms", direction, query.filterHash(),
            String.valueOf(score), "r1");
    }

    private void assertInvocationCause(Method method, ReservationListQuery query,
                                       SortedListCursor cursor) {
        assertThatThrownBy(() -> method.invoke(repository, query, cursor, null))
            .isInstanceOf(InvocationTargetException.class)
            .hasCauseInstanceOf(CyclesProtocolException.class);
    }

    private static Method privateMethod(String name, Class<?>... parameterTypes) throws Exception {
        Method method = RedisReservationQueryRepository.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }
}
