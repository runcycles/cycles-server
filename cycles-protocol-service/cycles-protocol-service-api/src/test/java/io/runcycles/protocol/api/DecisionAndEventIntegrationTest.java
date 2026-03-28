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

@DisplayName("Decision & Event Integration Tests")
class DecisionAndEventIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("POST /v1/decide")
    class Decide {

        @Test
        void shouldReturnAllowWhenBudgetAvailable() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("ALLOW");
        }

        @Test
        void shouldReturnDenyWhenBudgetInsufficient() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 99_999_999));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("DENY");
        }

        @Test
        void shouldReturnRateLimitHeaders() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
            assertThat(resp.getHeaders().getFirst("X-RateLimit-Reset")).isNotNull();
        }

        @Test
        void shouldRejectDecisionWithMissingSubject() {
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 100));

            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectCrossTenantDecision() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_B,
                    decisionBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldReturnSameDecisionOnReplay() {
            // Spec: On replay with same idempotency_key, MUST return original response
            Map<String, Object> body = decisionBody(TENANT_A, 1000);

            ResponseEntity<Map> resp1 = post("/v1/decide", API_KEY_SECRET_A, body);
            ResponseEntity<Map> resp2 = post("/v1/decide", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getBody().get("decision"))
                    .isEqualTo(resp1.getBody().get("decision"));
        }

        @Test
        void shouldReturnDenyWithReasonCodeWhenDebtOutstandingAndNoOverdraftLimit() {
            // Spec (decide): "If the subject scope has debt > 0 ... server SHOULD return
            // decision=DENY with reason_code=DEBT_OUTSTANDING"
            // This applies when overdraft_limit=0 (no policy allowing debt).
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "overdraft_limit", "0");
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "debt", "5000");
            }

            // /decide MUST NOT return 409 — returns 200 with decision=DENY
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 100));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("DENY");
            assertThat(resp.getBody().get("reason_code")).isEqualTo("DEBT_OUTSTANDING");
        }

        @Test
        void shouldReturnAllowWhenDebtWithinOverdraftLimit() {
            // Spec: "When debt > 0, new reservations MUST be rejected ... (unless explicitly
            // allowed by policy)." — overdraft_limit > 0 is the explicit policy.
            // /decide mirrors this: debt within limit → ALLOW.
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "debt", "5000");
            }

            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 100));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("ALLOW");
        }

        @Test
        void shouldReturnAffectedScopesInDecision() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("affected_scopes")).isNotNull();
        }
    }

    @Nested
    @DisplayName("POST /v1/events")
    class Events {

        @Test
        void shouldCreateEvent() {
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A,
                    eventBody(TENANT_A, 500));

            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            Map body = resp.getBody();
            assertThat(body.get("status")).isEqualTo("APPLIED");
            assertThat(body.get("event_id")).isNotNull();
        }

        @Test
        void shouldRejectEventWhenBudgetExceededWithRejectPolicy() {
            Map<String, Object> body = eventBody(TENANT_A, 99_999_999);
            body.put("overage_policy", "REJECT");
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldRejectCrossTenantEvent() {
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_B,
                    eventBody(TENANT_A, 500));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectEventWithMissingSubject() {
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("actual", Map.of("unit", "TOKENS", "amount", 500));

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectEventWithUnitMismatch() {
            // Spec: event actual.unit not supported for the target scope MUST return error
            // Budget is seeded for TOKENS only; USD_MICROCENTS has no budget → BUDGET_NOT_FOUND
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("actual", Map.of("unit", "USD_MICROCENTS", "amount", 500));

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            assertThat(resp.getBody().get("error")).isEqualTo("NOT_FOUND");
        }

        @Test
        void shouldReturnBalancesInEventResponse() {
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A,
                    eventBody(TENANT_A, 500));

            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            assertThat(resp.getBody().get("balances")).isNotNull();
        }

        @Test
        void shouldDeductFromBalanceAfterEvent() {
            ResponseEntity<Map> balanceBefore = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var beforeBalances = (java.util.List<Map<String, Object>>) balanceBefore.getBody().get("balances");
            Map<String, Object> beforeBal = beforeBalances.get(0);
            Map<String, Object> beforeRemaining = (Map<String, Object>) beforeBal.get("remaining");
            long remainingBefore = ((Number) beforeRemaining.get("amount")).longValue();

            post("/v1/events", API_KEY_SECRET_A, eventBody(TENANT_A, 5000));

            ResponseEntity<Map> balanceAfter = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var afterBalances = (java.util.List<Map<String, Object>>) balanceAfter.getBody().get("balances");
            Map<String, Object> afterBal = afterBalances.get(0);
            Map<String, Object> afterRemaining = (Map<String, Object>) afterBal.get("remaining");
            long remainingAfter = ((Number) afterRemaining.get("amount")).longValue();

            assertThat(remainingAfter).isLessThan(remainingBefore);
        }
    }
}
