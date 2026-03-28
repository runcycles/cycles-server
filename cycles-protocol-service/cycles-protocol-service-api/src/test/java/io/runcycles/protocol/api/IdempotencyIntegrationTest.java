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
        void shouldReturnSameCommitOnReplay() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body = commitBody(800);

            ResponseEntity<Map> resp1 = post(
                    "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, body);
            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp1.getBody().get("status")).isEqualTo("COMMITTED");
            assertThat(resp2.getBody().get("status")).isEqualTo("COMMITTED");
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
            ResponseEntity<Map> resp2 = post(
                    "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, body);

            assertThat(resp1.getStatusCode().value()).isEqualTo(200);
            assertThat(resp1.getBody().get("status")).isEqualTo("RELEASED");
            // Spec: idempotent replay with same key must return original successful response
            assertThat(resp2.getStatusCode().value()).isEqualTo(200);
            assertThat(resp2.getBody().get("status")).isEqualTo("RELEASED");
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
    }
}
