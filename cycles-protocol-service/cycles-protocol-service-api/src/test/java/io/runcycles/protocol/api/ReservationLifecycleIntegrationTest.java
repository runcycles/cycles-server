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

@DisplayName("Reservation Lifecycle Integration Tests")
class ReservationLifecycleIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("POST /v1/reservations — Create")
    class CreateReservation {

        @Test
        void shouldCreateReservation() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map body = resp.getBody();
            assertThat(body.get("decision")).isEqualTo("ALLOW");
            assertThat(body.get("reservation_id")).isNotNull();
            assertThat(body.get("expires_at_ms")).isNotNull();
            assertThat(body.get("affected_scopes")).isNotNull();
            assertThat(body.get("scope_path")).isNotNull();
            // Spec: reserved is a response field matching the estimate
            Map<String, Object> reserved = (Map<String, Object>) body.get("reserved");
            assertThat(reserved).isNotNull();
            assertThat(((Number) reserved.get("amount")).longValue()).isEqualTo(1000);
            assertThat(reserved.get("unit")).isEqualTo("TOKENS");
        }

        @Test
        void shouldReturnBalancesInResponse() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("balances")).isNotNull();
        }

        @Test
        void shouldRejectWhenBudgetExceeded() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 99_999_999));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldRejectMissingSubject() {
            Map<String, Object> body = Map.of(
                    "idempotency_key", UUID.randomUUID().toString(),
                    "action", Map.of("kind", "test", "name", "test"),
                    "estimate", Map.of("unit", "TOKENS", "amount", 100)
            );
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldSupportDryRun() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("dry_run", true);

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map respBody = resp.getBody();
            assertThat(respBody.get("decision")).isEqualTo("ALLOW");
            assertThat(respBody.get("reservation_id")).isNull();
            assertThat(respBody.get("expires_at_ms")).isNull();
            assertThat(respBody.get("affected_scopes")).isNotNull();
            // Spec: dry_run MUST return balances
            assertThat(respBody.get("balances")).isNotNull();
        }

        @Test
        void shouldSupportDryRunDeny() {
            Map<String, Object> body = reservationBody(TENANT_A, 99_999_999);
            body.put("dry_run", true);

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("DENY");
            assertThat(resp.getBody().get("affected_scopes")).isNotNull();
        }

        @Test
        void shouldNotModifyBalancesOnDryRun() {
            // Spec: dry_run MUST NOT modify balances, persist a reservation, or require commit/release
            ResponseEntity<Map> beforeResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var beforeBalances = (java.util.List<Map<String, Object>>) beforeResp.getBody().get("balances");
            long remainingBefore = ((Number) ((Map) beforeBalances.get(0).get("remaining")).get("amount")).longValue();
            long reservedBefore = ((Number) ((Map) beforeBalances.get(0).get("reserved")).get("amount")).longValue();

            // Dry-run a large reservation
            Map<String, Object> body = reservationBody(TENANT_A, 100_000);
            body.put("dry_run", true);
            post("/v1/reservations", API_KEY_SECRET_A, body);

            ResponseEntity<Map> afterResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var afterBalances = (java.util.List<Map<String, Object>>) afterResp.getBody().get("balances");
            long remainingAfter = ((Number) ((Map) afterBalances.get(0).get("remaining")).get("amount")).longValue();
            long reservedAfter = ((Number) ((Map) afterBalances.get(0).get("reserved")).get("amount")).longValue();

            assertThat(remainingAfter).isEqualTo(remainingBefore);
            assertThat(reservedAfter).isEqualTo(reservedBefore);
        }

        @Test
        void shouldRejectMissingAction() {
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 100));
            body.put("ttl_ms", 60000);

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectMissingEstimate() {
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("ttl_ms", 60000);

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectMissingIdempotencyKey() {
            Map<String, Object> body = new HashMap<>();
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 100));
            body.put("ttl_ms", 60000);

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectWhenNoBudgetExistsForUnit() {
            // No budget seeded for USD_MICROCENTS unit — Lua returns BUDGET_NOT_FOUND → 404
            Map<String, Object> body = reservationBody(TENANT_A, 1000, "USD_MICROCENTS");

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            assertThat(resp.getBody().get("error")).isEqualTo("NOT_FOUND");
        }

        @Test
        void shouldRejectSubjectWithOnlyDimensions() {
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("dimensions", Map.of("env", "prod")));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 100));
            body.put("ttl_ms", 60000);

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("POST /v1/reservations/{id}/commit — Commit")
    class CommitReservation {

        @Test
        void shouldCommitReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map body = resp.getBody();
            assertThat(body.get("status")).isEqualTo("COMMITTED");
            assertThat(body.get("charged")).isNotNull();
        }

        @Test
        void shouldReturnReleasedAmountWhenActualLessThanReserved() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(500));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("released")).isNotNull();
        }

        @Test
        void shouldRejectCommitWithUnitMismatch() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800, "USD_MICROCENTS"));

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(resp.getBody().get("error")).isEqualTo("UNIT_MISMATCH");
        }

        @Test
        void shouldRejectCommitOnFinalizedReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // First commit
            post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800));

            // Second commit should fail
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(100));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_FINALIZED");
        }

        @Test
        void shouldReturn404ForNonexistentReservation() {
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/nonexistent-id/commit",
                    API_KEY_SECRET_A, commitBody(100));

            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            assertThat(resp.getBody().get("error")).isEqualTo("NOT_FOUND");
        }

        @Test
        void shouldReturnBalancesInCommitResponse() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("balances")).isNotNull();
        }

        @Test
        void shouldNotReturnReleasedWhenActualEqualsReserved() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("released")).isNull();
        }
    }

    @Nested
    @DisplayName("POST /v1/reservations/{id}/release — Release")
    class ReleaseReservation {

        @Test
        void shouldReleaseReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map body = resp.getBody();
            assertThat(body.get("status")).isEqualTo("RELEASED");
            assertThat(body.get("released")).isNotNull();
        }

        @Test
        void shouldRejectReleaseOnFinalizedReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Commit first
            post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800));

            // Release should fail
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_FINALIZED");
        }

        @Test
        void shouldReturn404ForNonexistentRelease() {
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/nonexistent-id/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            assertThat(resp.getBody().get("error")).isEqualTo("NOT_FOUND");
        }

        @Test
        void shouldRejectDoubleRelease() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // First release
            post("/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            // Second release should fail
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_FINALIZED");
        }

        @Test
        void shouldReturnBalancesInReleaseResponse() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("balances")).isNotNull();
        }
    }

    @Nested
    @DisplayName("POST /v1/reservations/{id}/extend — Extend")
    class ExtendReservation {

        @Test
        void shouldExtendReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Capture original expiry and reserved amount
            ResponseEntity<Map> before = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            long originalExpiry = ((Number) before.getBody().get("expires_at_ms")).longValue();
            Map<String, Object> originalReserved = (Map<String, Object>) before.getBody().get("reserved");
            long originalAmount = ((Number) originalReserved.get("amount")).longValue();

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map body = resp.getBody();
            assertThat(body.get("status")).isEqualTo("ACTIVE");
            long newExpiry = ((Number) body.get("expires_at_ms")).longValue();
            assertThat(newExpiry).isGreaterThan(originalExpiry);

            // Spec: extend MUST NOT change reserved amount, subject, action, scope_path, affected_scopes
            ResponseEntity<Map> after = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            Map<String, Object> afterReserved = (Map<String, Object>) after.getBody().get("reserved");
            assertThat(((Number) afterReserved.get("amount")).longValue()).isEqualTo(originalAmount);
            assertThat(after.getBody().get("scope_path")).isEqualTo(before.getBody().get("scope_path"));
            assertThat(after.getBody().get("affected_scopes")).isEqualTo(before.getBody().get("affected_scopes"));
        }

        @Test
        void shouldRejectExtendOnFinalizedReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800));

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_FINALIZED");
        }

        @Test
        void shouldReturn404ForNonexistentExtend() {
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/nonexistent-id/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            assertThat(resp.getBody().get("error")).isEqualTo("NOT_FOUND");
        }

        @Test
        void shouldReturnBalancesInExtendResponse() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("balances")).isNotNull();
        }

        @Test
        void shouldRejectExtendOnReleasedReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            post("/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_FINALIZED");
        }
    }

    @Nested
    @DisplayName("GET /v1/reservations/{id} — Get")
    class GetReservation {

        @Test
        void shouldGetReservationDetails() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map body = resp.getBody();
            assertThat(body.get("reservation_id")).isEqualTo(reservationId);
            assertThat(body.get("status")).isEqualTo("ACTIVE");
            assertThat(body.get("subject")).isNotNull();
            assertThat(body.get("action")).isNotNull();
            assertThat(body.get("reserved")).isNotNull();
            assertThat(body.get("created_at_ms")).isNotNull();
            assertThat(body.get("expires_at_ms")).isNotNull();
            assertThat(body.get("scope_path")).isNotNull();
            assertThat(body.get("affected_scopes")).isNotNull();
        }

        @Test
        void shouldReturn404ForNonexistent() {
            ResponseEntity<Map> resp = get("/v1/reservations/nonexistent", API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            assertThat(resp.getBody().get("error")).isEqualTo("NOT_FOUND");
        }

        @Test
        void shouldReturnCommittedStatus() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800));

            ResponseEntity<Map> resp = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void shouldReturnReleasedStatus() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            post("/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            ResponseEntity<Map> resp = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("RELEASED");
        }
    }

    @Nested
    @DisplayName("GET /v1/reservations — List")
    class ListReservations {

        @Test
        void shouldListReservations() {
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 200);

            ResponseEntity<Map> resp = get("/v1/reservations?tenant=" + TENANT_A, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("reservations")).isNotNull();
        }

        @Test
        void shouldFilterByStatus() {
            String id1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 200);
            post("/v1/reservations/" + id1 + "/commit", API_KEY_SECRET_A, commitBody(100));

            ResponseEntity<Map> resp = get(
                    "/v1/reservations?status=ACTIVE", API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            var reservations = (java.util.List<?>) resp.getBody().get("reservations");
            assertThat(reservations).hasSize(1);
        }

        @Test
        void shouldRejectInvalidStatusFilter() {
            ResponseEntity<Map> resp = get(
                    "/v1/reservations?status=INVALID_STATUS", API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(resp.getBody().get("error")).isEqualTo("INVALID_REQUEST");
        }

        @Test
        void shouldListWithoutExplicitTenant() {
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

            ResponseEntity<Map> resp = get("/v1/reservations", API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("reservations")).isNotNull();
        }

        @Test
        void shouldReturnHasMoreField() {
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

            ResponseEntity<Map> resp = get("/v1/reservations?tenant=" + TENANT_A, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody()).containsKey("has_more");
        }

        @Test
        void shouldFilterByCommittedStatus() {
            String id1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 200);
            post("/v1/reservations/" + id1 + "/commit", API_KEY_SECRET_A, commitBody(100));

            ResponseEntity<Map> resp = get(
                    "/v1/reservations?status=COMMITTED", API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            var reservations = (java.util.List<?>) resp.getBody().get("reservations");
            assertThat(reservations).hasSize(1);
        }
    }
}
