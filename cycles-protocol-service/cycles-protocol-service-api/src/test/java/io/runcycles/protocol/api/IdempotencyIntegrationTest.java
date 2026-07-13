package io.runcycles.protocol.api;

import io.runcycles.protocol.data.service.ReservationExpiryService;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Idempotency Integration Tests")
class IdempotencyIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        void shouldReturnSameReservationOnReplay() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);

            ResponseEntity<Map> resp1 = post("/v1/reservations", API_KEY_SECRET_A, body);
            ResponseEntity<Map> resp2 = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getBody().get("reservation_id"))
                    .isEqualTo(resp1.getBody().get("reservation_id"));
        }

        @Test
        void reservePreservesInt64AmountBeyondRedisCjsonPrecision() {
            long amount = 100_000_000_000_001L;
            try (Jedis jedis = jedisPool.getResource()) {
                seedBudget(jedis, TENANT_A, "TOKENS", amount + 10);
            }
            Map<String, Object> body = reservationBody(TENANT_A, amount);

            ResponseEntity<Map> original = post("/v1/reservations", API_KEY_SECRET_A, body);
            ResponseEntity<Map> replay = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(original.getStatusCode().value()).isEqualTo(200);
            assertThat(((Map<?, ?>) original.getBody().get("reserved")).get("amount"))
                .isEqualTo(amount);
            Map<?, ?> balance = ((List<Map<?, ?>>) original.getBody().get("balances")).get(0);
            assertThat(((Number) ((Map<?, ?>) balance.get("reserved")).get("amount")).longValue())
                .isEqualTo(amount);
            assertThat(((Number) ((Map<?, ?>) balance.get("remaining")).get("amount")).longValue())
                .isEqualTo(10L);
            assertThat(replay.getBody()).isEqualTo(original.getBody());
        }

        @Test
        void commitPreservesInt64AmountsAndBalancesBeyondRedisCjsonPrecision() {
            long amount = 100_000_000_000_001L;
            try (Jedis jedis = jedisPool.getResource()) {
                seedBudget(jedis, TENANT_A, "TOKENS", amount + 10);
            }
            Map<String, Object> reserveBody = reservationBody(TENANT_A, amount - 1);
            reserveBody.put("overage_policy", "ALLOW_IF_AVAILABLE");
            ResponseEntity<Map> reserve = post(
                "/v1/reservations", API_KEY_SECRET_A, reserveBody);
            String reservationId = reserve.getBody().get("reservation_id").toString();
            Map<String, Object> commitBody = commitBody(amount);

            ResponseEntity<Map> original = post(
                "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, commitBody);
            ResponseEntity<Map> replay = post(
                "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, commitBody);

            assertThat(original.getStatusCode().value()).isEqualTo(200);
            assertThat(((Map<?, ?>) original.getBody().get("charged")).get("amount"))
                .isEqualTo(amount);
            Map<?, ?> balance = ((List<Map<?, ?>>) original.getBody().get("balances")).get(0);
            assertThat(((Number) ((Map<?, ?>) balance.get("remaining")).get("amount")).longValue())
                .isEqualTo(10L);
            assertThat(((Number) ((Map<?, ?>) balance.get("reserved")).get("amount")).longValue())
                .isZero();
            assertThat(((Number) ((Map<?, ?>) balance.get("spent")).get("amount")).longValue())
                .isEqualTo(amount);
            assertThat(replay.getBody()).isEqualTo(original.getBody());
        }

        @Test
        void commitSaturatesCrossScopeDebtAggregateWithoutPoisoningReplay() {
            long amount = Long.MAX_VALUE;
            String workspaceScope = "tenant:" + TENANT_A + "/workspace:dev";
            try (Jedis jedis = jedisPool.getResource()) {
                seedZeroBudgetWithOverdraft(jedis, "tenant:" + TENANT_A, "TOKENS", amount);
                seedZeroBudgetWithOverdraft(jedis, workspaceScope, "TOKENS", amount);
            }
            Map<String, Object> reserveBody = reservationBody(TENANT_A, 0);
            reserveBody.put("subject", Map.of("tenant", TENANT_A, "workspace", "dev"));
            reserveBody.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserve = post(
                "/v1/reservations", API_KEY_SECRET_A, reserveBody);
            assertThat(reserve.getStatusCode().value()).isEqualTo(200);
            String reservationId = reserve.getBody().get("reservation_id").toString();
            Map<String, Object> commitBody = commitBody(amount);

            ResponseEntity<Map> original = post(
                "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, commitBody);
            ResponseEntity<Map> replay = post(
                "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, commitBody);

            assertThat(original.getStatusCode().value()).isEqualTo(200);
            assertThat(((Map<?, ?>) original.getBody().get("charged")).get("amount"))
                .isEqualTo(amount);
            assertThat((List<Map<?, ?>>) original.getBody().get("balances"))
                .hasSize(2)
                .allSatisfy(balance -> {
                    assertThat(((Number) ((Map<?, ?>) balance.get("remaining")).get("amount")).longValue())
                        .isEqualTo(-amount);
                    assertThat(((Number) ((Map<?, ?>) balance.get("debt")).get("amount")).longValue())
                        .isEqualTo(amount);
                });
            assertThat(replay.getBody()).isEqualTo(original.getBody());
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.hget("reservation:res_" + reservationId, "debt_incurred"))
                    .isEqualTo(String.valueOf(Long.MAX_VALUE));
            }
        }

        @Test
        void releasePreservesInt64AmountsAndBalancesBeyondRedisCjsonPrecision() {
            long amount = 100_000_000_000_001L;
            try (Jedis jedis = jedisPool.getResource()) {
                seedBudget(jedis, TENANT_A, "TOKENS", amount + 10);
            }
            ResponseEntity<Map> reserve = post(
                "/v1/reservations", API_KEY_SECRET_A, reservationBody(TENANT_A, amount));
            String reservationId = reserve.getBody().get("reservation_id").toString();
            Map<String, Object> releaseBody = releaseBody();

            ResponseEntity<Map> original = post(
                "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, releaseBody);
            ResponseEntity<Map> replay = post(
                "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, releaseBody);

            assertThat(original.getStatusCode().value()).isEqualTo(200);
            assertThat(((Map<?, ?>) original.getBody().get("released")).get("amount"))
                .isEqualTo(amount);
            Map<?, ?> balance = ((List<Map<?, ?>>) original.getBody().get("balances")).get(0);
            assertThat(((Number) ((Map<?, ?>) balance.get("remaining")).get("amount")).longValue())
                .isEqualTo(amount + 10);
            assertThat(((Number) ((Map<?, ?>) balance.get("reserved")).get("amount")).longValue())
                .isZero();
            assertThat(replay.getBody()).isEqualTo(original.getBody());
        }

        private void seedZeroBudgetWithOverdraft(Jedis jedis, String scope, String unit,
                                                  long overdraftLimit) {
            jedis.hset("budget:" + scope + ":" + unit, Map.of(
                "scope", scope,
                "unit", unit,
                "allocated", "0",
                "remaining", "0",
                "reserved", "0",
                "spent", "0",
                "debt", "0",
                "overdraft_limit", String.valueOf(overdraftLimit),
                "is_over_limit", "false"));
        }

        @Test
        void pendingReserveSnapshotIsFinalizedAsOneCanonicalBaseResponse() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            ResponseEntity<Map> original = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = original.getBody().get("reservation_id").toString();

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del("reserve:body:" + reservationId);
                jedis.hdel("reservation:res_" + reservationId,
                    "reserve_evidence_id", "reserve_evidence_url");
                jedis.hset("reservation:res_" + reservationId,
                    "reserve_response_state", "PENDING");
            }

            ResponseEntity<Map> replay = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody()).doesNotContainKey("cycles_evidence");
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.hget("reservation:res_" + reservationId,
                    "reserve_response_state")).isEqualTo("BASE");
                assertThat(jedis.get("reserve:body:" + reservationId)).isNotNull();
            }
        }

        @Test
        void shouldReturnSameCommitOnReplay() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body = commitBody(800);

            ResponseEntity<Map> resp1 = post(
                    "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, body);
            // Rolling-upgrade compatibility: a pre-snapshot row can still replay
            // through its surviving canonical Java body cache.
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.hdel("reservation:res_" + reservationId, "commit_response_json"))
                        .isEqualTo(1L);
            }
            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp1.getBody().get("status")).isEqualTo("COMMITTED");
            assertThat(resp2.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void commitReplayUsesSnapshotWithoutReadingCurrentBudget() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body = commitBody(800);

            ResponseEntity<Map> original = post(
                    "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, body);
            assertThat(original.getStatusCode().value()).isEqualTo(200);
            replaceTenantBudgetWithWrongType();

            ResponseEntity<Map> replay = post(
                    "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, body);

            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody()).isEqualTo(original.getBody());
        }

        @Test
        void shouldRejectIdempotencyKeyMismatch() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headersWithIdempotencyKey(
                            API_KEY_SECRET_A, "different-key")),
                    Map.class
            );

            // Spec: header/body idempotency key mismatch is a request validation error (400),
            // not a replay payload mismatch (409 IDEMPOTENCY_MISMATCH)
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(resp.getBody().get("error")).isEqualTo("INVALID_REQUEST");
        }

        @Test
        void shouldReturnSameReleaseOnReplay() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body = releaseBody();

            ResponseEntity<Map> resp1 = post(
                    "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, body);
            // A pre-snapshot row can still replay through its surviving canonical body cache.
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.hdel("reservation:res_" + reservationId, "release_response_json"))
                        .isEqualTo(1L);
            }
            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp1.getBody().get("status")).isEqualTo("RELEASED");
            // Spec: idempotent replay with same key must return original successful response
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getBody().get("status")).isEqualTo("RELEASED");
        }

        @Test
        void releaseReplayUsesSnapshotWithoutReadingCurrentBudget() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body = releaseBody();

            ResponseEntity<Map> original = post(
                    "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, body);
            assertThat(original.getStatusCode().value()).isEqualTo(200);
            replaceTenantBudgetWithWrongType();

            ResponseEntity<Map> replay = post(
                    "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, body);

            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody()).isEqualTo(original.getBody());
        }

        @Test
        void shouldReturnSameExtendOnReplay() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body = extendBody(30000);

            ResponseEntity<Map> resp1 = post(
                    "/v1/reservations/" + reservationId + "/extend", API_KEY_SECRET_A, body);
            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/extend", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp1.getBody().get("status")).isEqualTo("ACTIVE");
            assertThat(resp2.getBody().get("status")).isEqualTo("ACTIVE");
        }

        @Test
        void shouldHandleEventIdempotency() {
            Map<String, Object> body = eventBody(TENANT_A, 500);

            ResponseEntity<Map> resp1 = post("/v1/events", API_KEY_SECRET_A, body);
            ResponseEntity<Map> resp2 = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(201);
            assertThat(resp2.getStatusCode().value()).isEqualTo(201);
            assertThat(resp2.getBody().get("event_id"))
                    .isEqualTo(resp1.getBody().get("event_id"));
        }

        @Test
        void shouldHandleDryRunIdempotency() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("dry_run", true);

            ResponseEntity<Map> resp1 = post("/v1/reservations", API_KEY_SECRET_A, body);
            ResponseEntity<Map> resp2 = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp1.getBody().get("decision")).isEqualTo(resp2.getBody().get("decision"));
        }

        @Test
        void shouldRejectCommitIdempotencyMismatch() {
            // Spec: same key with different payload MUST return 409 IDEMPOTENCY_MISMATCH
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);
            Map<String, Object> body1 = commitBody(3000);
            String sharedKey = (String) body1.get("idempotency_key");

            // First commit succeeds
            ResponseEntity<Map> resp1 = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, body1);
            assertThat(resp1.getStatusCode().value()).isEqualTo(200);

            // Second commit with same key but different amount
            Map<String, Object> body2 = commitBody(1000);
            body2.put("idempotency_key", sharedKey);

            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, body2);
            assertThat(resp2.getStatusCode().value()).isEqualTo(409);
            assertThat(resp2.getBody().get("error")).isEqualTo("IDEMPOTENCY_MISMATCH");
        }

        @Test
        void shouldRejectReservationIdempotencyMismatch() {
            // Spec: same key with different payload MUST return 409 IDEMPOTENCY_MISMATCH
            Map<String, Object> body1 = reservationBody(TENANT_A, 1000);
            String sharedKey = (String) body1.get("idempotency_key");

            // First request succeeds
            ResponseEntity<Map> resp1 = post("/v1/reservations", API_KEY_SECRET_A, body1);
            assertThat(resp1.getStatusCode().value()).isEqualTo(200);

            // Second request with same key but different amount
            Map<String, Object> body2 = reservationBody(TENANT_A, 2000);
            body2.put("idempotency_key", sharedKey);

            ResponseEntity<Map> resp2 = post("/v1/reservations", API_KEY_SECRET_A, body2);
            assertThat(resp2.getStatusCode().value()).isEqualTo(409);
            assertThat(resp2.getBody().get("error")).isEqualTo("IDEMPOTENCY_MISMATCH");
        }

        @Test
        void shouldRejectEventIdempotencyMismatch() {
            // Spec: same key with different payload MUST return 409 IDEMPOTENCY_MISMATCH
            Map<String, Object> body1 = eventBody(TENANT_A, 500);
            String sharedKey = (String) body1.get("idempotency_key");

            ResponseEntity<Map> resp1 = post("/v1/events", API_KEY_SECRET_A, body1);
            assertThat(resp1.getStatusCode().value()).isEqualTo(201);

            // Same key, different amount
            Map<String, Object> body2 = eventBody(TENANT_A, 999);
            body2.put("idempotency_key", sharedKey);

            ResponseEntity<Map> resp2 = post("/v1/events", API_KEY_SECRET_A, body2);
            assertThat(resp2.getStatusCode().value()).isEqualTo(409);
            assertThat(resp2.getBody().get("error")).isEqualTo("IDEMPOTENCY_MISMATCH");
        }

        @Test
        void shouldRejectExtendIdempotencyMismatch() {
            // Spec: same key with different payload MUST return 409 IDEMPOTENCY_MISMATCH
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body1 = extendBody(30000);
            String sharedKey = (String) body1.get("idempotency_key");

            ResponseEntity<Map> resp1 = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, body1);
            assertThat(resp1.getStatusCode().value()).isEqualTo(200);

            // Same key, different extend_by_ms
            Map<String, Object> body2 = extendBody(60000);
            body2.put("idempotency_key", sharedKey);

            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, body2);
            assertThat(resp2.getStatusCode().value()).isEqualTo(409);
            assertThat(resp2.getBody().get("error")).isEqualTo("IDEMPOTENCY_MISMATCH");
        }

        @Test
        void shouldRejectReleaseIdempotencyMismatch() {
            // Spec: same key with different payload MUST return 409 IDEMPOTENCY_MISMATCH
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body1 = releaseBody();
            String sharedKey = (String) body1.get("idempotency_key");

            ResponseEntity<Map> resp1 = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, body1);
            assertThat(resp1.getStatusCode().value()).isEqualTo(200);

            // Same key, different reason
            Map<String, Object> body2 = new HashMap<>();
            body2.put("idempotency_key", sharedKey);
            body2.put("reason", "completely-different-reason");

            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, body2);
            assertThat(resp2.getStatusCode().value()).isEqualTo(409);
            assertThat(resp2.getBody().get("error")).isEqualTo("IDEMPOTENCY_MISMATCH");
        }

        @Test
        void shouldRejectDecideIdempotencyMismatch() {
            // Spec: same key with different payload MUST return 409 IDEMPOTENCY_MISMATCH
            Map<String, Object> body1 = decisionBody(TENANT_A, 1000);
            String sharedKey = (String) body1.get("idempotency_key");

            ResponseEntity<Map> resp1 = post("/v1/decide", API_KEY_SECRET_A, body1);
            assertThat(resp1.getStatusCode().value()).isEqualTo(200);

            // Same key, different amount
            Map<String, Object> body2 = decisionBody(TENANT_A, 5000);
            body2.put("idempotency_key", sharedKey);

            ResponseEntity<Map> resp2 = post("/v1/decide", API_KEY_SECRET_A, body2);
            assertThat(resp2.getStatusCode().value()).isEqualTo(409);
            assertThat(resp2.getBody().get("error")).isEqualTo("IDEMPOTENCY_MISMATCH");
        }

        private HttpHeaders headersWithIdempotencyKey(
                String apiKey, String idempotencyKey) {
            var headers = headersForTenant(apiKey);
            headers.set("X-Idempotency-Key", idempotencyKey);
            return headers;
        }

        private void replaceTenantBudgetWithWrongType() {
            try (Jedis jedis = jedisPool.getResource()) {
                String budgetKey = "budget:tenant:" + TENANT_A + ":TOKENS";
                jedis.del(budgetKey);
                jedis.set(budgetKey, "wrong-type-sentinel");
            }
        }
    }
}
