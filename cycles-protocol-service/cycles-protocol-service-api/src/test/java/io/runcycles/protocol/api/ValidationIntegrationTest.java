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

@DisplayName("Validation Integration Tests")
class ValidationIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("Input Validation")
    class InputValidation {

        @Test
        void shouldRejectUnknownJsonProperties() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("unknown_extra_field", "should-be-rejected");

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectUnknownFieldsInCommitBody() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            Map<String, Object> body = commitBody(800);
            body.put("unexpected_field", 42);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectUnknownFieldsInEventBody() {
            Map<String, Object> body = eventBody(TENANT_A, 500);
            body.put("phantom_field", "nope");

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectUnknownFieldsInReleaseBody() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            Map<String, Object> body = releaseBody();
            body.put("extra_field", "not-in-spec");

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectUnknownFieldsInExtendBody() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            Map<String, Object> body = extendBody(30000);
            body.put("bogus", true);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectUnknownFieldsInDecideBody() {
            Map<String, Object> body = decisionBody(TENANT_A, 1000);
            body.put("unknown_decide_field", 42);

            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectOversizedReservationIdOnGet() {
            // 129-char ID exceeds @Size(max=128)
            String longId = "x".repeat(129);

            ResponseEntity<Map> resp = get(
                    "/v1/reservations/" + longId, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectOversizedReservationIdOnCommit() {
            String longId = "x".repeat(129);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + longId + "/commit",
                    API_KEY_SECRET_A, commitBody(100));

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectOversizedReservationIdOnRelease() {
            String longId = "x".repeat(129);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + longId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectOversizedReservationIdOnExtend() {
            String longId = "x".repeat(129);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + longId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldAcceptMaxLengthReservationId() {
            // 128-char ID is valid (will return 404 since it doesn't exist, not 400)
            String maxId = "a".repeat(128);

            ResponseEntity<Map> resp = get(
                    "/v1/reservations/" + maxId, API_KEY_SECRET_A);

            // Should be 404 (not found), not 400 (validation error)
            assertThat(resp.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        void shouldRejectMalformedJsonBody() {
            HttpHeaders headers = headersForTenant(API_KEY_SECRET_A);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    HttpMethod.POST,
                    new HttpEntity<>("{invalid json!!!}", headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(resp.getBody().get("error")).isEqualTo("INVALID_REQUEST");
        }

        @Test
        void shouldRejectMalformedJsonOnEvent() {
            HttpHeaders headers = headersForTenant(API_KEY_SECRET_A);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/events",
                    HttpMethod.POST,
                    new HttpEntity<>("{not: valid, json", headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(resp.getBody().get("error")).isEqualTo("INVALID_REQUEST");
        }
    }

    @Nested
    @DisplayName("Error Response Format")
    class ErrorResponseFormat {

        @Test
        void shouldReturnStructuredErrorOnBudgetExceeded() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 99_999_999));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            Map body = resp.getBody();
            assertThat(body).containsKey("error");
            assertThat(body).containsKey("message");
            assertThat(body).containsKey("request_id");
        }

        @Test
        void shouldReturnStructuredErrorOnNotFound() {
            ResponseEntity<Map> resp = get("/v1/reservations/nonexistent", API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            Map body = resp.getBody();
            assertThat(body.get("error")).isEqualTo("NOT_FOUND");
            assertThat(body).containsKey("message");
            assertThat(body).containsKey("request_id");
        }

        @Test
        void shouldReturnStructuredErrorOnValidationFailure() {
            Map<String, Object> body = Map.of(
                    "idempotency_key", UUID.randomUUID().toString(),
                    "action", Map.of("kind", "test", "name", "test"),
                    "estimate", Map.of("unit", "TOKENS", "amount", 100)
            );
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            Map respBody = resp.getBody();
            assertThat(respBody).containsKey("error");
            assertThat(respBody).containsKey("message");
        }

        @Test
        void shouldReturnStructuredErrorOnUnauthorized() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    HttpMethod.POST,
                    new HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(401);
            Map body = resp.getBody();
            assertThat(body).containsKey("error");
            assertThat(body).containsKey("message");
        }
    }

    @Nested
    @DisplayName("Error Precedence")
    class ErrorPrecedence {

        @Test
        void shouldReturnOverdraftLimitExceededWhenOverLimit() {
            // Spec: when is_over_limit=true, return OVERDRAFT_LIMIT_EXCEEDED even if debt > 0
            // We need to set is_over_limit=true directly since the Lua pre-checks prevent
            // sequential commits from exceeding the limit (only concurrent ops can).
            // Budget: allocated=1_000_000, overdraft_limit=100_000

            // Create debt that exactly matches overdraft_limit, then manually set is_over_limit
            String drain = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 950_000);
            post("/v1/reservations/" + drain + "/commit", API_KEY_SECRET_A, commitBody(950_000));

            Map<String, Object> overdraftBody = reservationBody(TENANT_A, 1000);
            overdraftBody.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, overdraftBody);
            String overdraftResId = (String) reserveResp.getBody().get("reservation_id");
            post("/v1/reservations/" + overdraftResId + "/commit",
                    API_KEY_SECRET_A, commitBody(100_000));
            // debt=50_000 > 0, is_over_limit=false (50k ≤ 100k limit)

            // Manually set is_over_limit=true to simulate concurrent race
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "is_over_limit", "true");
            }

            // New reservation should get OVERDRAFT_LIMIT_EXCEEDED (not DEBT_OUTSTANDING)
            // because is_over_limit check comes first in reserve.lua
            ResponseEntity<Map> newResp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));

            assertThat(newResp.getStatusCode().value()).isEqualTo(409);
            assertThat(newResp.getBody().get("error")).isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        }

        @Test
        void shouldReturnDebtOutstandingWhenNotOverLimitAndNoOverdraftLimit() {
            // Spec precedence: is_over_limit check → DEBT_OUTSTANDING check → BUDGET_EXCEEDED.
            // "When is_over_limit=true, server MUST return OVERDRAFT_LIMIT_EXCEEDED ...
            //  This takes precedence over DEBT_OUTSTANDING."
            // Here is_over_limit=false, overdraft_limit=0, debt>0 → DEBT_OUTSTANDING.
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "overdraft_limit", "0");
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "debt", "5000");
            }

            ResponseEntity<Map> newResp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));

            assertThat(newResp.getStatusCode().value()).isEqualTo(409);
            assertThat(newResp.getBody().get("error")).isEqualTo("DEBT_OUTSTANDING");
        }
    }
}
