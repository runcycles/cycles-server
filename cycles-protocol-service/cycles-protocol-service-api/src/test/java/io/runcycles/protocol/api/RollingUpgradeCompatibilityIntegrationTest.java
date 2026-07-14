package io.runcycles.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Current-reader versus frozen legacy-Redis-shape compatibility matrix.
 *
 * <p>This intentionally runs only the current server. It pins the supported
 * storage shapes at rolling-upgrade seams without requiring old application
 * binaries or a timing-sensitive mixed-fleet deployment.</p>
 */
@DisplayName("Rolling-upgrade Redis compatibility")
@TestPropertySource(properties = {
        "cycles.reservation-index.created-at.enabled=true",
        "cycles.reservation-index.created-at.initial-delay-ms=999999999"
})
class RollingUpgradeCompatibilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReservationCreatedAtIndexService createdAtIndexService;

    @ParameterizedTest(name = "{0} replays a legacy fast body byte-for-byte")
    @EnumSource(LifecycleOperation.class)
    void lifecycleFastBodyCompatibility(LifecycleOperation operation) throws Exception {
        LifecycleCall call = executeLifecycle(operation);
        Map<String, String> budgetAfterMutation = getBudgetFromRedis(
                "tenant:" + TENANT_A, "TOKENS");

        try (Jedis jedis = jedisPool.getResource()) {
            LegacyRedisFixtures.lifecycleFastBodyOnly(
                    jedis, call.reservationId(), operation.storageName);
        }

        ResponseEntity<String> replay = postRaw(call.path(), call.request());

        assertThat(replay.getStatusCode()).isEqualTo(call.original().getStatusCode());
        assertThat(replay.getBody()).isEqualTo(call.original().getBody());
        assertThat(getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS"))
                .isEqualTo(budgetAfterMutation);
    }

    @ParameterizedTest(name = "{0} reports the accepted pre-snapshot missing-body window")
    @EnumSource(LifecycleOperation.class)
    void lifecycleMissingOriginalBodyFailsSafely(LifecycleOperation operation) throws Exception {
        LifecycleCall call = executeLifecycle(operation);
        Map<String, String> budgetAfterMutation = getBudgetFromRedis(
                "tenant:" + TENANT_A, "TOKENS");
        try (Jedis jedis = jedisPool.getResource()) {
            LegacyRedisFixtures.lifecycleMappingWithoutOriginalBody(
                    jedis, call.reservationId(), operation.storageName);
        }

        ResponseEntity<String> replay = postRaw(call.path(), call.request());

        assertThat(replay.getStatusCode().value()).isEqualTo(500);
        JsonNode error = objectMapper.readTree(replay.getBody());
        assertThat(error.path("error").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.path("message").asText())
                .contains("temporarily unavailable")
                .contains("same idempotency_key");
        assertThat(getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS"))
                .isEqualTo(budgetAfterMutation);
    }

    @Test
    @DisplayName("legacy event fast body replays and backfills the immutable snapshot")
    void legacyEventFastBodyIsBackfilled() throws Exception {
        Map<String, Object> request = eventBody(TENANT_A, 500);
        String idempotencyKey = request.get("idempotency_key").toString();
        ResponseEntity<String> original = postRaw("/v1/events", request);
        String eventId = objectMapper.readTree(original.getBody()).path("event_id").asText();

        try (Jedis jedis = jedisPool.getResource()) {
            LegacyRedisFixtures.eventFastBodyOnly(
                    jedis, TENANT_A, eventId, idempotencyKey);
        }

        ResponseEntity<String> replay = postRaw("/v1/events", request);

        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(original.getBody());
        try (Jedis jedis = jedisPool.getResource()) {
            String mappingKey = "idem:" + TENANT_A + ":event:" + idempotencyKey;
            assertThat(jedis.hget("event:evt_" + eventId, "event_response_json"))
                    .isEqualTo(jedis.get(mappingKey + ":response"));
        }
    }

    @Test
    @DisplayName("snapshot-less legacy event fails safely without inviting a second debit")
    void legacyEventWithoutAnyOriginalBodyFailsSafely() throws Exception {
        Map<String, Object> request = eventBody(TENANT_A, 500);
        String idempotencyKey = request.get("idempotency_key").toString();
        ResponseEntity<String> original = postRaw("/v1/events", request);
        String eventId = objectMapper.readTree(original.getBody()).path("event_id").asText();
        Map<String, String> budgetAfterMutation = getBudgetFromRedis(
                "tenant:" + TENANT_A, "TOKENS");
        try (Jedis jedis = jedisPool.getResource()) {
            LegacyRedisFixtures.eventMappingWithoutOriginalBody(
                    jedis, TENANT_A, eventId, idempotencyKey);
        }

        ResponseEntity<String> replay = postRaw("/v1/events", request);

        assertThat(replay.getStatusCode().value()).isEqualTo(500);
        JsonNode error = objectMapper.readTree(replay.getBody());
        assertThat(error.path("error").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.path("message").asText())
                .contains("do not retry automatically")
                .contains("do not", "reuse");
        assertThat(getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS"))
                .isEqualTo(budgetAfterMutation);
    }

    @Test
    @DisplayName("legacy dry-run namespace preserves replay and mismatch semantics")
    void legacyDryRunNamespaceIsDualRead() throws Exception {
        Map<String, Object> request = reservationBody(TENANT_A, 1_000);
        request.put("dry_run", true);
        String idempotencyKey = request.get("idempotency_key").toString();
        ResponseEntity<String> original = postRaw("/v1/reservations", request);
        try (Jedis jedis = jedisPool.getResource()) {
            LegacyRedisFixtures.moveDryRunToLegacyNamespace(
                    jedis, TENANT_A, idempotencyKey);
        }

        ResponseEntity<String> replay = postRaw("/v1/reservations", request);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(original.getBody());

        Map<String, Object> changed = new HashMap<>(request);
        changed.put("estimate", Map.of("amount", 1_001, "unit", "TOKENS"));
        assertMismatch(postRaw("/v1/reservations", changed));

        Map<String, Object> live = new HashMap<>(request);
        live.put("dry_run", false);
        assertMismatch(postRaw("/v1/reservations", live));
    }

    @Test
    @DisplayName("reservation hashes written before the created-at index backfill to READY")
    void legacyReservationBackfillsCreatedAtIndex() {
        try (Jedis jedis = jedisPool.getResource()) {
            LegacyRedisFixtures.reservationBeforeCreatedAtIndex(
                    jedis, "legacy-index-row", TENANT_A, 1_000L);
        }

        ReservationCreatedAtIndexService.ReconcileResult result =
                createdAtIndexService.reconcileNow();

        assertThat(result.tenantsFailed()).isZero();
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.zscore(
                    ReservationCreatedAtIndexService.indexKey(TENANT_A),
                    "legacy-index-row")).isEqualTo(1_000D);
            assertThat(createdAtIndexService.isReady(jedis, TENANT_A)).isTrue();
        }
        ResponseEntity<Map> indexedRead = get(
                "/v1/reservations?limit=10&sort_by=created_at_ms&sort_dir=desc",
                API_KEY_SECRET_A);
        assertThat(indexedRead.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) indexedRead.getBody().get("reservations");
        assertThat(rows).extracting(row -> row.get("reservation_id"))
                .containsExactly("legacy-index-row");
    }

    private LifecycleCall executeLifecycle(LifecycleOperation operation) throws Exception {
        if (operation == LifecycleOperation.RESERVE) {
            Map<String, Object> request = reservationBody(TENANT_A, 1_000);
            ResponseEntity<String> original = postRaw("/v1/reservations", request);
            assertThat(original.getStatusCode().value()).isEqualTo(200);
            String reservationId = objectMapper.readTree(original.getBody())
                    .path("reservation_id").asText();
            return new LifecycleCall(reservationId, "/v1/reservations", request, original);
        }

        String reservationId = createReservationAndGetId(
                TENANT_A, API_KEY_SECRET_A, 1_000);
        Map<String, Object> request = operation == LifecycleOperation.COMMIT
                ? commitBody(800) : releaseBody();
        String path = "/v1/reservations/" + reservationId + "/" + operation.storageName;
        ResponseEntity<String> original = postRaw(path, request);
        assertThat(original.getStatusCode().value()).isEqualTo(200);
        return new LifecycleCall(reservationId, path, request, original);
    }

    private ResponseEntity<String> postRaw(String path, Map<String, Object> body) {
        return restTemplate.exchange(
                baseUrl() + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headersForTenant(API_KEY_SECRET_A)),
                String.class);
    }

    private void assertMismatch(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.getBody()).path("error").asText())
                .isEqualTo("IDEMPOTENCY_MISMATCH");
    }

    private enum LifecycleOperation {
        RESERVE("reserve"),
        COMMIT("commit"),
        RELEASE("release");

        private final String storageName;

        LifecycleOperation(String storageName) {
            this.storageName = storageName;
        }
    }

    private record LifecycleCall(String reservationId,
                                 String path,
                                 Map<String, Object> request,
                                 ResponseEntity<String> original) {
    }
}
