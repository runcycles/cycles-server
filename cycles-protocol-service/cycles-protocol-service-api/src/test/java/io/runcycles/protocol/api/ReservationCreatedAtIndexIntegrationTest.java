package io.runcycles.protocol.api;

import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reservation created-at index")
@TestPropertySource(properties = {
    "cycles.reservation-index.created-at.enabled=true",
    "cycles.reservation-index.created-at.initial-delay-ms=999999999"
})
class ReservationCreatedAtIndexIntegrationTest extends BaseIntegrationTest {

    @Autowired private ReservationCreatedAtIndexService indexService;

    @Test
    @DisplayName("writers before and after readiness keep the completeness count exact")
    void writersAcrossReadinessBoundaryRemainComplete() {
        seedReservation("legacy", TENANT_A, 1_000L, "ACTIVE");
        String beforeReady = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

        ReservationCreatedAtIndexService.ReconcileResult result = indexService.reconcileNow();
        assertThat(result.tenantsFailed()).isZero();

        String afterReady = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = ReservationCreatedAtIndexService.indexKey(TENANT_A);
            String metaKey = ReservationCreatedAtIndexService.metadataKey(TENANT_A);
            assertThat(jedis.zscore(indexKey, "legacy")).isEqualTo(1_000.0);
            assertThat(jedis.zscore(indexKey, beforeReady)).isNotNull();
            assertThat(jedis.zscore(indexKey, afterReady)).isNotNull();
            assertThat(jedis.hget(metaKey, "state")).isEqualTo("READY");
            assertThat(jedis.hget(metaKey, "expected_count")).isEqualTo("3");
            assertThat(jedis.zcard(indexKey)).isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("empty readiness transitions atomically on the tenant's first reserve")
    void firstWriterTransitionsReadyEmptyIndex() {
        ResponseEntity<Map> empty = get(
            "/v1/reservations?limit=10&sort_by=created_at_ms&sort_dir=desc",
            API_KEY_SECRET_A);
        assertThat(reservationIds(empty)).isEmpty();
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(ReservationCreatedAtIndexService.indexKey(TENANT_A))).isFalse();
            assertThat(jedis.hget(ReservationCreatedAtIndexService.metadataKey(TENANT_A),
                "expected_count")).isEqualTo("0");
        }

        String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = ReservationCreatedAtIndexService.indexKey(TENANT_A);
            String metaKey = ReservationCreatedAtIndexService.metadataKey(TENANT_A);
            assertThat(jedis.zscore(indexKey, reservationId)).isNotNull();
            assertThat(jedis.hget(metaKey, "expected_count")).isEqualTo("1");
            assertThat(indexService.isReady(jedis, TENANT_A)).isTrue();
        }
    }

    @Test
    @DisplayName("commit and release keep terminal reservations indexed through retention")
    void terminalReservationsRemainListable() {
        String committed = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
        String released = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
        assertThat(indexService.reconcileNow().tenantsFailed()).isZero();

        assertThat(post("/v1/reservations/" + committed + "/commit",
            API_KEY_SECRET_A, commitBody(100)).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/v1/reservations/" + released + "/release",
            API_KEY_SECRET_A, releaseBody()).getStatusCode().value()).isEqualTo(200);

        assertThat(reservationIds(get(
            "/v1/reservations?status=COMMITTED&sort_by=created_at_ms&sort_dir=desc",
            API_KEY_SECRET_A))).containsExactly(committed);
        assertThat(reservationIds(get(
            "/v1/reservations?status=RELEASED&sort_by=created_at_ms&sort_dir=desc",
            API_KEY_SECRET_A))).containsExactly(released);
        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = ReservationCreatedAtIndexService.indexKey(TENANT_A);
            assertThat(jedis.zscore(indexKey, committed)).isNotNull();
            assertThat(jedis.zscore(indexKey, released)).isNotNull();
        }
    }

    @Test
    @DisplayName("concurrent reserves serialize with readiness publication")
    void concurrentWritersAtReadinessBoundaryRemainComplete() throws Exception {
        seedReservation("legacy", TENANT_A, 1_000L, "ACTIVE");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> writes;
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            writes = java.util.stream.IntStream.range(0, 40)
                .mapToObj(ignored -> executor.submit(() -> {
                    start.await();
                    return createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
                }))
                .toList();
            start.countDown();
            assertThat(indexService.reconcileNow().tenantsFailed()).isZero();
            for (Future<String> write : writes) {
                assertThat(write.get()).isNotBlank();
            }
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = ReservationCreatedAtIndexService.indexKey(TENANT_A);
            String metaKey = ReservationCreatedAtIndexService.metadataKey(TENANT_A);
            assertThat(jedis.zcard(indexKey)).isEqualTo(41L);
            assertThat(jedis.hget(metaKey, "expected_count")).isEqualTo("41");
            assertThat(indexService.isReady(jedis, TENANT_A)).isTrue();
        }
    }

    @Test
    @DisplayName("missing member invalidates readiness and falls back without omitting rows")
    void missingMemberFallsBackToFullScan() {
        seedReservation("r1", TENANT_A, 1_000L, "ACTIVE");
        seedReservation("r2", TENANT_A, 2_000L, "ACTIVE");
        indexService.reconcileNow();

        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.zrem(ReservationCreatedAtIndexService.indexKey(TENANT_A), "r1"))
                .isEqualTo(1L);
        }

        ResponseEntity<Map> response = get(
            "/v1/reservations?limit=10&sort_by=created_at_ms&sort_dir=desc",
            API_KEY_SECRET_A);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(reservationIds(response)).containsExactly("r2", "r1");
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey(TENANT_A))).isFalse();
        }
    }

    @Test
    @DisplayName("equal timestamps retain reservation-id ascending ties in both directions")
    void equalTimestampPaginationMatchesComparator() {
        seedReservation("a", TENANT_A, 2_000L, "ACTIVE");
        seedReservation("b", TENANT_A, 2_000L, "ACTIVE");
        seedReservation("c", TENANT_A, 2_000L, "ACTIVE");
        seedReservation("d", TENANT_A, 1_000L, "ACTIVE");
        indexService.reconcileNow();

        ResponseEntity<Map> desc1 = get(
            "/v1/reservations?limit=2&sort_by=created_at_ms&sort_dir=desc",
            API_KEY_SECRET_A);
        assertThat(reservationIds(desc1)).containsExactly("a", "b");
        String descCursor = (String) desc1.getBody().get("next_cursor");
        ResponseEntity<Map> desc2 = get(
            "/v1/reservations?limit=2&sort_by=created_at_ms&sort_dir=desc&cursor=" + descCursor,
            API_KEY_SECRET_A);
        assertThat(reservationIds(desc2)).containsExactly("c", "d");

        ResponseEntity<Map> asc1 = get(
            "/v1/reservations?limit=3&sort_by=created_at_ms&sort_dir=asc",
            API_KEY_SECRET_A);
        assertThat(reservationIds(asc1)).containsExactly("d", "a", "b");
        String ascCursor = (String) asc1.getBody().get("next_cursor");
        ResponseEntity<Map> asc2 = get(
            "/v1/reservations?limit=3&sort_by=created_at_ms&sort_dir=asc&cursor=" + ascCursor,
            API_KEY_SECRET_A);
        assertThat(reservationIds(asc2)).containsExactly("c");
    }

    @Test
    @DisplayName("selective filters continue across multiple bounded index batches")
    void selectiveFilterContinuesAcrossBatches() {
        for (int i = 0; i < 300; i++) {
            String state = i == 0 || i == 129 || i == 258 ? "COMMITTED" : "ACTIVE";
            seedReservation(String.format("r%03d", i), TENANT_A, 10_000L + i, state);
        }
        indexService.reconcileNow();

        ResponseEntity<Map> first = get(
            "/v1/reservations?limit=2&status=COMMITTED&sort_by=created_at_ms&sort_dir=desc",
            API_KEY_SECRET_A);
        assertThat(reservationIds(first)).containsExactly("r258", "r129");
        String cursor = (String) first.getBody().get("next_cursor");
        ResponseEntity<Map> second = get(
            "/v1/reservations?limit=2&status=COMMITTED&sort_by=created_at_ms&sort_dir=desc&cursor=" + cursor,
            API_KEY_SECRET_A);
        assertThat(reservationIds(second)).containsExactly("r000");
    }

    @Test
    @DisplayName("malformed backfill row prevents readiness for its tenant")
    void malformedBackfillDoesNotPublishReadiness() {
        seedReservation("valid", TENANT_A, 1_000L, "ACTIVE");
        seedReservation("invalid", TENANT_A, 2_000L, "ACTIVE");
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset("reservation:res_invalid", "created_at", "not-an-int64");
        }

        ReservationCreatedAtIndexService.ReconcileResult result = indexService.reconcileNow();
        assertThat(result.tenantsFailed()).isEqualTo(1);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(ReservationCreatedAtIndexService.metadataKey(TENANT_A))).isFalse();
        }
    }

    @Test
    @DisplayName("wrong-type optional index cannot fail a successful reserve")
    void wrongTypeIndexFailsOpenForWriteAndClosedForIndexedRead() {
        String indexKey = ReservationCreatedAtIndexService.indexKey(TENANT_A);
        String metaKey = ReservationCreatedAtIndexService.metadataKey(TENANT_A);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(indexKey, "wrong-type");
            jedis.hset(metaKey, Map.of("state", "READY", "expected_count", "0"));
        }

        String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists("reservation:res_" + reservationId)).isTrue();
            assertThat(jedis.type(indexKey)).isEqualTo("string");
            assertThat(jedis.exists(metaKey)).isFalse();
        }
    }

    @Test
    @DisplayName("stale sweep removes missing hashes and repairs the expected count")
    void staleSweepRepairsCount() {
        seedReservation("r1", TENANT_A, 1_000L, "ACTIVE");
        seedReservation("r2", TENANT_A, 2_000L, "ACTIVE");
        indexService.reconcileNow();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("reservation:res_r1");
        }

        indexService.sweepStaleMembers();

        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = ReservationCreatedAtIndexService.indexKey(TENANT_A);
            String metaKey = ReservationCreatedAtIndexService.metadataKey(TENANT_A);
            assertThat(jedis.zscore(indexKey, "r1")).isNull();
            assertThat(jedis.zscore(indexKey, "r2")).isEqualTo(2_000.0);
            assertThat(jedis.hget(metaKey, "expected_count")).isEqualTo("1");
            assertThat(indexService.isReady(jedis, TENANT_A)).isTrue();
        }
    }

    private void seedReservation(String reservationId, String tenant, long createdAt, String state) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset("reservation:res_" + reservationId, Map.ofEntries(
                Map.entry("reservation_id", reservationId),
                Map.entry("tenant", tenant),
                Map.entry("state", state),
                Map.entry("subject_json", "{\"tenant\":\"" + tenant + "\"}"),
                Map.entry("action_json", "{\"kind\":\"llm.completion\",\"name\":\"index-test\"}"),
                Map.entry("estimate_amount", "100"),
                Map.entry("estimate_unit", "TOKENS"),
                Map.entry("scope_path", "tenant:" + tenant),
                Map.entry("affected_scopes", "[\"tenant:" + tenant + "\"]"),
                Map.entry("created_at", String.valueOf(createdAt)),
                Map.entry("expires_at", String.valueOf(createdAt + 60_000L))));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> reservationIds(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return ((List<Map<String, Object>>) response.getBody().get("reservations")).stream()
            .map(row -> (String) row.get("reservation_id"))
            .toList();
    }
}
