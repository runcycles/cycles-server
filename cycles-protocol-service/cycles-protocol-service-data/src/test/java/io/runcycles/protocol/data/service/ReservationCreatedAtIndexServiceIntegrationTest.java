package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.repository.support.SortedListCursor;
import io.runcycles.protocol.model.ReservationListResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ReservationCreatedAtIndexService with Redis")
class ReservationCreatedAtIndexServiceIntegrationTest {

    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static JedisPool jedisPool;
    private ReservationCreatedAtIndexService indexService;
    private RedisReservationRepository repository;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        if (jedisPool != null) jedisPool.close();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
        }
        String indexScript;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("lua/reservation-created-at-index.lua")) {
            assertThat(input).isNotNull();
            indexScript = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        LuaScriptRegistry registry = new LuaScriptRegistry();

        indexService = new ReservationCreatedAtIndexService();
        setField(indexService, "jedisPool", jedisPool);
        setField(indexService, "luaScripts", registry);
        setField(indexService, "indexScript", indexScript);
        setField(indexService, "enabled", true);

        repository = new RedisReservationRepository();
        setField(repository, "jedisPool", jedisPool);
        setField(repository, "objectMapper", new ObjectMapper());
        setField(repository, "reservationCreatedAtIndex", indexService);
        setField(repository, "metrics", new CyclesMetrics(new SimpleMeterRegistry(), false));
    }

    @Test
    void reconcilesValidRowsAndDetectsCountDrift() {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        seed("r2", "tenant-a", 2_000L, "ACTIVE");

        ReservationCreatedAtIndexService.ReconcileResult result = indexService.reconcileNow();
        assertThat(result).isEqualTo(new ReservationCreatedAtIndexService.ReconcileResult(2, 1, 0));
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(indexService.isReady(jedis, "tenant-a")).isTrue();
            assertThat(jedis.hget(ReservationCreatedAtIndexService.metadataKey("tenant-a"),
                "expected_count")).isEqualTo("2");
            jedis.zrem(ReservationCreatedAtIndexService.indexKey("tenant-a"), "r1");
            assertThat(indexService.isReady(jedis, "tenant-a")).isFalse();
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey("tenant-a"))).isFalse();
        }
    }

    @Test
    void repairSchedulerIsRestartableAndDisabledModeIsNoOp() throws Exception {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        indexService.repairIfRequested();
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(indexService.isReady(jedis, "tenant-a")).isTrue();
        }

        setField(indexService, "enabled", false);
        indexService.requestRepair();
        indexService.repairIfRequested();
        assertThat(indexService.reconcileNow())
            .isEqualTo(new ReservationCreatedAtIndexService.ReconcileResult(0, 0, 0));
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(indexService.isReady(jedis, "tenant-a")).isFalse();
            indexService.removeStaleMembers(jedis, "tenant-a", List.of("r1"));
        }
        indexService.sweepStaleMembers();
    }

    @Test
    void authoritativeEmptyScanPublishesReadyZeroAndTransitionsOnFirstWrite() {
        assertThat(ids(list("tenant-empty", null, 10, null, "desc", null, null))).isEmpty();
        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = ReservationCreatedAtIndexService.indexKey("tenant-empty");
            String metaKey = ReservationCreatedAtIndexService.metadataKey("tenant-empty");
            assertThat(jedis.exists(indexKey)).isFalse();
            assertThat(jedis.hget(metaKey, "state")).isEqualTo("READY");
            assertThat(jedis.hget(metaKey, "expected_count")).isEqualTo("0");
            assertThat(indexService.isReady(jedis, "tenant-empty")).isTrue();
            indexService.removeStaleMembers(jedis, "tenant-empty", List.of("already-absent"));
        }
        assertThat(ids(list("tenant-empty", null, 10, null, "desc", null, null))).isEmpty();

        seed("first", "tenant-empty", 1_000L, "ACTIVE");
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(
                "redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2]); "
                    + "return redis.call('HINCRBY', KEYS[2], 'expected_count', 1)",
                List.of(ReservationCreatedAtIndexService.indexKey("tenant-empty"),
                    ReservationCreatedAtIndexService.metadataKey("tenant-empty")),
                List.of("1000", "first"));
        }
        assertThat(ids(list("tenant-empty", null, 10, null, "desc", null, null)))
            .containsExactly("first");
    }

    @Test
    void malformedRowsAndWrongTypeIndexNeverPublishReadiness() {
        seed("bad", "tenant-a", 1_000L, "ACTIVE");
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset("reservation:res_bad", "created_at", "bad-number");
        }
        assertThat(indexService.reconcileNow().tenantsFailed()).isEqualTo(1);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey("tenant-a"))).isFalse();
            jedis.set(ReservationCreatedAtIndexService.indexKey("tenant-a"), "wrong-type");
        }
        seed("valid", "tenant-a", 2_000L, "ACTIVE");
        assertThat(indexService.reconcileNow().tenantsFailed()).isEqualTo(1);
        try (Jedis jedis = jedisPool.getResource()) {
            indexService.invalidate(jedis, "tenant-a");
            assertThat(indexService.isReady(jedis, "tenant-a")).isFalse();
        }
    }

    @Test
    void backfillRejectsMissingIdentityTenantAndUnsafeScores() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset("reservation:res_no-tenant", Map.of(
                "reservation_id", "no-tenant", "created_at", "1000"));
            jedis.set("reservation:res_wrong-type", "not-a-hash");
        }
        seed("mismatch", "tenant-a", 1_000L, "ACTIVE");
        seed("unsafe", "tenant-b", 9_007_199_254_740_993L, "ACTIVE");
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset("reservation:res_mismatch", "reservation_id", "different");
        }

        ReservationCreatedAtIndexService.ReconcileResult result = indexService.reconcileNow();

        assertThat(result.keysScanned()).isEqualTo(4);
        assertThat(result.tenantsReady()).isZero();
        assertThat(result.tenantsFailed()).isEqualTo(2);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey("tenant-a"))).isFalse();
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey("tenant-b"))).isFalse();
        }
    }

    @Test
    void repairSchedulerRetriesFailedAndExceptionalRuns() throws Exception {
        seed("bad", "tenant-a", 1_000L, "ACTIVE");
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset("reservation:res_bad", "created_at", "not-a-number");
        }
        indexService.repairIfRequested();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset("reservation:res_bad", "created_at", "1000");
        }
        indexService.repairIfRequested();
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(indexService.isReady(jedis, "tenant-a")).isTrue();
        }

        JedisPool workingPool = jedisPool;
        JedisPool brokenPool = mock(JedisPool.class);
        when(brokenPool.getResource()).thenThrow(new IllegalStateException("redis unavailable"));
        setField(indexService, "jedisPool", brokenPool);
        indexService.requestRepair();
        indexService.repairIfRequested();
        setField(indexService, "jedisPool", workingPool);
        indexService.repairIfRequested();
    }

    @Test
    void sweepRemovesStaleRowsAndCorrectsScores() {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        seed("r2", "tenant-a", 2_000L, "ACTIVE");
        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("reservation:res_r1");
            jedis.zadd(ReservationCreatedAtIndexService.indexKey("tenant-a"), 9_999.0, "r2");
        }

        indexService.sweepStaleMembers();

        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = ReservationCreatedAtIndexService.indexKey("tenant-a");
            assertThat(jedis.zscore(indexKey, "r1")).isNull();
            assertThat(jedis.zscore(indexKey, "r2")).isEqualTo(2_000.0);
            assertThat(jedis.hget(ReservationCreatedAtIndexService.metadataKey("tenant-a"),
                "expected_count")).isEqualTo("1");
            assertThat(indexService.isReady(jedis, "tenant-a")).isTrue();
        }
    }

    @Test
    void sweepQuarantinesInvalidIndexesAndContinuesWithHealthyTenants() {
        seed("healthy", "tenant-c", 3_000L, "ACTIVE");
        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("reservation:res_healthy");
            jedis.set(ReservationCreatedAtIndexService.indexKey("tenant-a"), "wrong-type");
            jedis.hset(ReservationCreatedAtIndexService.metadataKey("tenant-a"), "state", "READY");

            seedWith(jedis, "unsafe", "tenant-b", 9_007_199_254_740_993L, "ACTIVE");
            jedis.zadd(ReservationCreatedAtIndexService.indexKey("tenant-b"),
                9_007_199_254_740_992.0, "unsafe");
            jedis.hset(ReservationCreatedAtIndexService.metadataKey("tenant-b"), Map.of(
                "state", "READY", "expected_count", "1"));
            jedis.zadd("reservation:idx::created_at_ms", 1.0, "ignored");
        }

        indexService.sweepStaleMembers();

        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey("tenant-a"))).isFalse();
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey("tenant-b"))).isFalse();
            assertThat(jedis.zcard(ReservationCreatedAtIndexService.indexKey("tenant-c"))).isZero();
            indexService.removeStaleMembers(jedis, "tenant-a", List.of("anything"));

            Jedis closed = jedisPool.getResource();
            closed.close();
            assertThat(indexService.isReady(closed, "tenant-a")).isFalse();
            indexService.invalidate(jedis, null);
            indexService.invalidate(jedis, " ");
        }
    }

    @Test
    void indexedReaderPreservesBothDirectionsAndCursorTies() {
        seed("a", "tenant-a", 2_000L, "ACTIVE");
        seed("b", "tenant-a", 2_000L, "ACTIVE");
        seed("c", "tenant-a", 2_000L, "ACTIVE");
        seed("d", "tenant-a", 1_000L, "ACTIVE");
        indexService.reconcileNow();

        ReservationListResponse desc1 = list("tenant-a", null, 2, null, "desc", null, null);
        assertThat(ids(desc1)).containsExactly("a", "b");
        assertThat(desc1.getHasMore()).isTrue();
        ReservationListResponse desc2 = list(
            "tenant-a", null, 2, desc1.getNextCursor(), "desc", null, null);
        assertThat(ids(desc2)).containsExactly("c", "d");

        ReservationListResponse asc1 = list("tenant-a", null, 3, null, "asc", null, null);
        assertThat(ids(asc1)).containsExactly("d", "a", "b");
        ReservationListResponse asc2 = list(
            "tenant-a", null, 3, asc1.getNextCursor(), "asc", null, null);
        assertThat(ids(asc2)).containsExactly("c");
    }

    @Test
    void indexedReaderScansBoundedBatchesForSelectiveFilters() {
        for (int i = 0; i < 300; i++) {
            seed(String.format("r%03d", i), "tenant-a", 10_000L + i,
                i == 0 || i == 129 || i == 258 ? "COMMITTED" : "ACTIVE");
        }
        indexService.reconcileNow();

        ReservationListResponse first = list("tenant-a", "COMMITTED", 2, null, "desc", null, null);
        assertThat(ids(first)).containsExactly("r258", "r129");
        ReservationListResponse second = list(
            "tenant-a", "COMMITTED", 2, first.getNextCursor(), "desc", null, null);
        assertThat(ids(second)).containsExactly("r000");
    }

    @Test
    void indexedReaderPaginatesAllTenThousandRowsWithoutDuplicates() {
        seedPopulation("tenant-a", 10_000);
        assertThat(indexService.reconcileNow().tenantsFailed()).isZero();

        List<String> allIds = new java.util.ArrayList<>(10_000);
        String cursor = null;
        int pages = 0;
        do {
            ReservationListResponse page = list(
                "tenant-a", null, 100, cursor, "desc", null, null);
            allIds.addAll(ids(page));
            cursor = page.getNextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(100);
        assertThat(allIds).hasSize(10_000).doesNotHaveDuplicates();
        assertThat(allIds.get(0)).isEqualTo("r09999");
        assertThat(allIds.get(allIds.size() - 1)).isEqualTo("r00000");
    }

    @Test
    void equalScoreGroupLargerThanBatchKeepsIdAscendingAcrossPages() {
        try (Jedis jedis = jedisPool.getResource(); Pipeline pipeline = jedis.pipelined()) {
            for (int i = 0; i < 300; i++) {
                seedWith(pipeline, String.format("r%03d", i), "tenant-a", 10_000L, "ACTIVE");
            }
            pipeline.sync();
        }
        indexService.reconcileNow();
        List<String> expected = java.util.stream.IntStream.range(0, 300)
            .mapToObj(i -> String.format("r%03d", i)).toList();

        for (String direction : List.of("asc", "desc")) {
            List<String> actual = new java.util.ArrayList<>(300);
            String cursor = null;
            do {
                ReservationListResponse page = list(
                    "tenant-a", null, 100, cursor, direction, null, null);
                actual.addAll(ids(page));
                cursor = page.getNextCursor();
            } while (cursor != null);
            assertThat(actual).containsExactlyElementsOf(expected);

            String missingMemberCursor = new SortedListCursor(
                1, "created_at_ms", direction, filterHashForWindow("tenant-a", null, null),
                "10000", "r149x").encode();
            assertThat(ids(list("tenant-a", null, 10, missingMemberCursor,
                direction, null, null))).containsExactlyElementsOf(expected.subList(150, 160));
        }
    }

    @Test
    void scoreDriftAndMissingMembersFallBackWithoutOmission() {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        seed("r2", "tenant-a", 2_000L, "ACTIVE");
        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(ReservationCreatedAtIndexService.indexKey("tenant-a"), 3_000.0, "r1");
        }

        ReservationListResponse driftFallback = list("tenant-a", null, 10, null, "desc", null, null);
        assertThat(ids(driftFallback)).containsExactly("r2", "r1");
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey("tenant-a"))).isFalse();
        }

        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("reservation:res_r1");
        }
        ReservationListResponse staleCleanup = list("tenant-a", null, 10, null, "desc", null, null);
        assertThat(ids(staleCleanup)).containsExactly("r2");
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.zcard(ReservationCreatedAtIndexService.indexKey("tenant-a"))).isEqualTo(1L);
            assertThat(indexService.isReady(jedis, "tenant-a")).isTrue();
        }
    }

    @Test
    void indexedReaderRepairsCrossTenantPointersAndFallsBackOnIdentityDrift() {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        seed("other", "tenant-b", 2_000L, "ACTIVE");
        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(ReservationCreatedAtIndexService.indexKey("tenant-a"), 2_000.0, "other");
            jedis.hincrBy(ReservationCreatedAtIndexService.metadataKey("tenant-a"),
                "expected_count", 1);
        }

        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("r1");
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.zscore(
                ReservationCreatedAtIndexService.indexKey("tenant-a"), "other")).isNull();
            jedis.hset("reservation:res_r1", "reservation_id", "different");
        }

        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("different");
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(
                ReservationCreatedAtIndexService.metadataKey("tenant-a"))).isFalse();
        }
    }

    @Test
    void missingMetadataOrEvictedIndexFallsBackWithoutOmission() {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        seed("r2", "tenant-a", 2_000L, "ACTIVE");
        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(ReservationCreatedAtIndexService.metadataKey("tenant-a"));
        }
        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("r2", "r1");

        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(ReservationCreatedAtIndexService.indexKey("tenant-a"));
        }
        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("r2", "r1");
    }

    @Test
    void indexedReaderFallsBackForUnavailableChangingAndInvalidIndexes() throws Exception {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("r1");

        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(ReservationCreatedAtIndexService.indexKey("tenant-a"), 1_000.5, "r1");
        }
        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("r1");

        ReservationCreatedAtIndexService changing = mock(ReservationCreatedAtIndexService.class);
        when(changing.isEnabled()).thenReturn(true);
        when(changing.isReady(any(Jedis.class), eq("tenant-a"))).thenReturn(true, false);
        setField(repository, "reservationCreatedAtIndex", changing);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(ReservationCreatedAtIndexService.indexKey("tenant-a"));
            jedis.zadd(ReservationCreatedAtIndexService.indexKey("tenant-a"), 1_000.0, "r1");
        }
        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("r1");

        ReservationCreatedAtIndexService failing = mock(ReservationCreatedAtIndexService.class);
        when(failing.isEnabled()).thenReturn(true);
        when(failing.isReady(any(Jedis.class), eq("tenant-a"))).thenReturn(true);
        when(failing.readPage(any(Jedis.class), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyInt())).thenThrow(new IllegalStateException("wrong type"));
        setField(repository, "reservationCreatedAtIndex", failing);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(ReservationCreatedAtIndexService.indexKey("tenant-a"));
            jedis.set(ReservationCreatedAtIndexService.indexKey("tenant-a"), "wrong-type");
        }
        assertThat(ids(list("tenant-a", null, 10, null, "desc", null, null)))
            .containsExactly("r1");
    }

    @Test
    void indexedReaderValidatesCursorAndAppliesEveryScopeFilter() {
        seed("good", "tenant-a", 2_000L, "COMMITTED");
        seed("malformed", "tenant-a", 1_000L, "COMMITTED");
        try (Jedis jedis = jedisPool.getResource()) {
            for (String id : List.of("good", "malformed")) {
                jedis.hset("reservation:res_" + id, Map.of(
                    "scope_path", "tenant:tenant-a/workspace:ws/app:app/workflow:wf/agent:ag/toolset:ts",
                    "idempotency_key", "idem-1",
                    "expires_at", "62000",
                    "committed_at", "2500"));
            }
            jedis.hset("reservation:res_malformed", "action_json", "not-json");
        }
        indexService.reconcileNow();

        ReservationListResponse filtered = repository.listReservations(
            "tenant-a", "idem-1", "COMMITTED", "ws", "app", "wf", "ag", "ts",
            10, null, "created_at_ms", "asc",
            500L, 2_500L, 61_000L, 63_000L, 2_000L, 3_000L);
        assertThat(ids(filtered)).containsExactly("good");

        String invalid = new SortedListCursor(
            1, "created_at_ms", "desc", filterHashForWindow("tenant-a", null, null),
            "not-a-number", "good").encode();
        assertThatThrownBy(() -> list("tenant-a", null, 10, invalid, "desc", null, null))
            .isInstanceOf(CyclesProtocolException.class)
            .hasMessageContaining("invalid created_at_ms boundary");

        String belowWindow = new SortedListCursor(
            1, "created_at_ms", "desc", filterHashForWindow("tenant-a", 1_500L, 2_500L),
            "1000", "malformed").encode();
        assertThat(ids(list("tenant-a", null, 10, belowWindow, "desc", 1_500L, 2_500L)))
            .isEmpty();
    }

    @Test
    void createdAtWindowAndOutOfRangeCursorStayBounded() {
        seed("r1", "tenant-a", 1_000L, "ACTIVE");
        seed("r2", "tenant-a", 2_000L, "ACTIVE");
        seed("r3", "tenant-a", 3_000L, "ACTIVE");
        indexService.reconcileNow();

        assertThat(ids(list("tenant-a", null, 10, null, "asc", 1_500L, 2_500L)))
            .containsExactly("r2");
        String beyond = new SortedListCursor(
            1, "created_at_ms", "asc", filterHashForWindow("tenant-a", 1_500L, 2_500L),
            "3000", "r3").encode();
        assertThat(ids(list("tenant-a", null, 10, beyond, "asc", 1_500L, 2_500L))).isEmpty();
    }

    @Test
    void keyAndScoreHelpersRejectUnsafeValues() {
        assertThat(ReservationCreatedAtIndexService.indexKey("acme"))
            .isEqualTo("reservation:idx:acme:created_at_ms");
        assertThat(ReservationCreatedAtIndexService.metadataKey("acme"))
            .isEqualTo("reservation:idxmeta:acme:created_at_ms");
        assertThat(ReservationCreatedAtIndexService.isExactRedisScore(9_007_199_254_740_992L)).isTrue();
        assertThat(ReservationCreatedAtIndexService.isExactRedisScore(9_007_199_254_740_993L)).isFalse();
        assertThat(ReservationCreatedAtIndexService.isExactRedisScore(-9_007_199_254_740_993L)).isFalse();
    }

    private ReservationListResponse list(String tenant, String status, int limit, String cursor,
                                         String direction, Long fromMs, Long toMs) {
        return repository.listReservations(
            tenant, null, status, null, null, null, null, null,
            limit, cursor, "created_at_ms", direction,
            fromMs, toMs, null, null, null, null);
    }

    private static String filterHashForWindow(String tenant, Long fromMs, Long toMs) {
        return io.runcycles.protocol.data.repository.support.FilterHasher.hash(
            tenant, null, null, null, null, null, null, null,
            fromMs, toMs, null, null, null, null);
    }

    private void seed(String id, String tenant, long createdAt, String state) {
        try (Jedis jedis = jedisPool.getResource()) {
            seedWith(jedis, id, tenant, createdAt, state);
        }
    }

    private void seedPopulation(String tenant, int count) {
        try (Jedis jedis = jedisPool.getResource(); Pipeline pipeline = jedis.pipelined()) {
            for (int i = 0; i < count; i++) {
                seedWith(pipeline, String.format("r%05d", i), tenant, 10_000L + i, "ACTIVE");
            }
            pipeline.sync();
        }
    }

    private static void seedWith(Jedis jedis, String id, String tenant, long createdAt, String state) {
        jedis.hset("reservation:res_" + id, reservationFields(id, tenant, createdAt, state));
    }

    private static void seedWith(Pipeline pipeline, String id, String tenant,
                                 long createdAt, String state) {
        pipeline.hset("reservation:res_" + id, reservationFields(id, tenant, createdAt, state));
    }

    private static Map<String, String> reservationFields(String id, String tenant,
                                                          long createdAt, String state) {
        return Map.ofEntries(
            Map.entry("reservation_id", id),
            Map.entry("tenant", tenant),
            Map.entry("state", state),
            Map.entry("subject_json", "{\"tenant\":\"" + tenant + "\"}"),
            Map.entry("action_json", "{\"kind\":\"llm.completion\",\"name\":\"test\"}"),
            Map.entry("estimate_amount", "100"),
            Map.entry("estimate_unit", "TOKENS"),
            Map.entry("scope_path", "tenant:" + tenant),
            Map.entry("affected_scopes", "[\"tenant:" + tenant + "\"]"),
            Map.entry("created_at", String.valueOf(createdAt)),
            Map.entry("expires_at", String.valueOf(createdAt + 60_000L)));
    }

    private static List<String> ids(ReservationListResponse response) {
        return response.getReservations().stream().map(r -> r.getReservationId()).toList();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
