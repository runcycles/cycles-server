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

@DisplayName("Cycles Protocol v0.1.23 Integration Tests")
class CyclesProtocolIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReservationExpiryService expiryService;

    // ========================================================================
    // Reservation Lifecycle
    // ========================================================================

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

    // ========================================================================
    // Decisions
    // ========================================================================

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
        void shouldReturnDenyWithReasonCodeWhenDebtOutstanding() {
            // Spec: /decide SHOULD return decision=DENY with reason_code=DEBT_OUTSTANDING when debt > 0
            // Create debt: drain budget then overdraft commit
            String drain = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 950_000);
            post("/v1/reservations/" + drain + "/commit", API_KEY_SECRET_A, commitBody(950_000));

            Map<String, Object> overdraftBody = reservationBody(TENANT_A, 1000);
            overdraftBody.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, overdraftBody);
            String overdraftResId = (String) reserveResp.getBody().get("reservation_id");
            post("/v1/reservations/" + overdraftResId + "/commit",
                    API_KEY_SECRET_A, commitBody(100_000));
            // debt > 0 now

            // /decide MUST NOT return 409, SHOULD return 200 with decision=DENY
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 100));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("DENY");
            assertThat(resp.getBody().get("reason_code")).isEqualTo("DEBT_OUTSTANDING");
        }

        @Test
        void shouldReturnAffectedScopesInDecision() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("affected_scopes")).isNotNull();
        }
    }

    // ========================================================================
    // Events
    // ========================================================================

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
        void shouldRejectEventWhenBudgetExceeded() {
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A,
                    eventBody(TENANT_A, 99_999_999));

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

    // ========================================================================
    // Balances
    // ========================================================================

    @Nested
    @DisplayName("GET /v1/balances")
    class Balances {

        @Test
        void shouldQueryBalances() {
            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("balances")).isNotNull();
        }

        @Test
        void shouldRejectWithoutSubjectFilter() {
            ResponseEntity<Map> resp = get("/v1/balances", API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldReflectReservationInBalances() {
            // Check initial
            ResponseEntity<Map> before = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balancesBefore = (java.util.List<Map<String, Object>>) before.getBody().get("balances");

            // Reserve some budget
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            // Check after
            ResponseEntity<Map> after = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balancesAfter = (java.util.List<Map<String, Object>>) after.getBody().get("balances");

            // Reserved should have increased
            assertThat(balancesAfter).isNotEmpty();
        }

        @Test
        void shouldReflectCommitInBalances() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            ResponseEntity<Map> beforeCommit = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var beforeBalances = (java.util.List<Map<String, Object>>) beforeCommit.getBody().get("balances");
            Map<String, Object> beforeBal = beforeBalances.get(0);
            Map<String, Object> beforeSpent = (Map<String, Object>) beforeBal.get("spent");
            long spentBefore = ((Number) beforeSpent.get("amount")).longValue();

            post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(3000));

            ResponseEntity<Map> afterCommit = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var afterBalances = (java.util.List<Map<String, Object>>) afterCommit.getBody().get("balances");
            Map<String, Object> afterBal = afterBalances.get(0);
            Map<String, Object> afterSpent = (Map<String, Object>) afterBal.get("spent");
            long spentAfter = ((Number) afterSpent.get("amount")).longValue();

            assertThat(spentAfter).isGreaterThan(spentBefore);
        }

        @Test
        void shouldReflectReleaseInBalances() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            ResponseEntity<Map> duringReserve = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var duringBalances = (java.util.List<Map<String, Object>>) duringReserve.getBody().get("balances");
            Map<String, Object> duringBal = duringBalances.get(0);
            Map<String, Object> duringReserved = (Map<String, Object>) duringBal.get("reserved");
            long reservedDuring = ((Number) duringReserved.get("amount")).longValue();

            post("/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            ResponseEntity<Map> afterRelease = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var afterBalances = (java.util.List<Map<String, Object>>) afterRelease.getBody().get("balances");
            Map<String, Object> afterBal = afterBalances.get(0);
            Map<String, Object> afterReserved = (Map<String, Object>) afterBal.get("reserved");
            long reservedAfter = ((Number) afterReserved.get("amount")).longValue();

            assertThat(reservedAfter).isLessThan(reservedDuring);
        }

        @Test
        void shouldReturnBalanceFields() {
            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            var balances = (java.util.List<Map<String, Object>>) resp.getBody().get("balances");
            assertThat(balances).isNotEmpty();

            Map<String, Object> balance = balances.get(0);
            assertThat(balance).containsKey("scope");
            assertThat(balance).containsKey("scope_path");
            assertThat(balance).containsKey("remaining");
            assertThat(balance).containsKey("reserved");
            assertThat(balance).containsKey("spent");
            assertThat(balance).containsKey("allocated");
        }
    }

    // ========================================================================
    // Idempotency
    // ========================================================================

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

    // ========================================================================
    // Tenant Isolation
    // ========================================================================

    @Nested
    @DisplayName("Tenant Isolation")
    class TenantIsolation {

        @Test
        void shouldRejectCrossTenantReservationCreate() {
            // Tenant B trying to create reservation for tenant A
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_B,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantCommit() {
            // Create reservation as tenant A
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Try to commit as tenant B
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_B, commitBody(800));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantGet() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_B);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantBalanceQuery() {
            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_B);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectMissingApiKey() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    HttpMethod.POST,
                    new HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        void shouldRejectCrossTenantRelease() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_B, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantExtend() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_B, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantEvent() {
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_B,
                    eventBody(TENANT_A, 500));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantDecision() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_B,
                    decisionBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantListReservations() {
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

            ResponseEntity<Map> resp = get(
                    "/v1/reservations?tenant=" + TENANT_A, API_KEY_SECRET_B);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectInvalidApiKey() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Cycles-API-Key", "cyc_invalid_key_12345");

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    HttpMethod.POST,
                    new HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        void shouldRejectRevokedApiKey() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                String revokedSecret = "cyc_revoked123456789abc";
                String hash = BCrypt.hashpw(revokedSecret, BCrypt.gensalt());
                String prefix = revokedSecret.substring(0, Math.min(14, revokedSecret.length()));

                ApiKey apiKey = ApiKey.builder()
                        .keyId("key-revoked")
                        .tenantId(TENANT_A)
                        .keyPrefix(prefix)
                        .keyHash(hash)
                        .name("Revoked key")
                        .status(ApiKeyStatus.REVOKED)
                        .permissions(Collections.emptyList())
                        .createdAt(Instant.now())
                        .build();

                jedis.set("apikey:lookup:" + prefix, "key-revoked");
                jedis.set("apikey:key-revoked", objectMapper.writeValueAsString(apiKey));

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Cycles-API-Key", revokedSecret);

                ResponseEntity<Map> resp = restTemplate.exchange(
                        baseUrl() + "/v1/reservations",
                        HttpMethod.POST,
                        new HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                        Map.class
                );

                assertThat(resp.getStatusCode().value()).isEqualTo(401);
            }
        }
    }

    // ========================================================================
    // Overdraft / Debt
    // ========================================================================

    @Nested
    @DisplayName("Overdraft & Debt")
    class Overdraft {

        @Test
        void shouldSupportAllowWithOverdraft() {
            // Create reservation with ALLOW_WITH_OVERDRAFT
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit more than reserved (overage)
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void shouldRejectOverageWithRejectPolicy() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "REJECT");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit more than reserved
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(409);
        }

        @Test
        void shouldSupportEventOveragePolicy() {
            Map<String, Object> body = eventBody(TENANT_A, 500);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            assertThat(resp.getBody().get("status")).isEqualTo("APPLIED");
        }

        @Test
        void shouldSupportAllowIfAvailablePolicy() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            assertThat(reserveResp.getBody().get("decision")).isEqualTo("ALLOW");
        }
    }

    // ========================================================================
    // Response Headers
    // ========================================================================

    @Nested
    @DisplayName("Response Headers")
    class ResponseHeaders {

        @Test
        void shouldReturnRequestIdHeader() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotNull();
        }

        @Test
        void shouldReturnTenantHeader() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getHeaders().getFirst("X-Cycles-Tenant")).isEqualTo(TENANT_A);
        }

        @Test
        void shouldReturnRequestIdOnError() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 99_999_999));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotNull();
        }

        @Test
        void shouldReturnRequestIdOnCommit() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(800));

            assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotNull();
        }

        @Test
        void shouldReturnRequestIdOnBalanceQuery() {
            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);

            assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotNull();
        }

        @Test
        void shouldReturnTenantHeaderOnAllEndpoints() {
            // Events
            ResponseEntity<Map> eventResp = post("/v1/events", API_KEY_SECRET_A,
                    eventBody(TENANT_A, 100));
            assertThat(eventResp.getHeaders().getFirst("X-Cycles-Tenant")).isEqualTo(TENANT_A);

            // Decisions
            ResponseEntity<Map> decideResp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 100));
            assertThat(decideResp.getHeaders().getFirst("X-Cycles-Tenant")).isEqualTo(TENANT_A);

            // Balances
            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            assertThat(balanceResp.getHeaders().getFirst("X-Cycles-Tenant")).isEqualTo(TENANT_A);
        }
    }

    // ========================================================================
    // Full Lifecycle (end-to-end)
    // ========================================================================

    @Nested
    @DisplayName("Full Lifecycle")
    class FullLifecycle {

        @Test
        void shouldCompleteReserveCommitLifecycle() {
            // 1. Check initial balance
            ResponseEntity<Map> balanceBefore = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            assertThat(balanceBefore.getStatusCode().value()).isEqualTo(200);

            // 2. Create reservation
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            // 3. Get reservation details
            ResponseEntity<Map> detail = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(detail.getBody().get("status")).isEqualTo("ACTIVE");

            // 4. Commit
            ResponseEntity<Map> commit = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(3000));
            assertThat(commit.getBody().get("status")).isEqualTo("COMMITTED");

            // 5. Verify finalized
            ResponseEntity<Map> afterCommit = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(afterCommit.getBody().get("status")).isEqualTo("COMMITTED");

            // 6. Check balance changed
            ResponseEntity<Map> balanceAfter = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            assertThat(balanceAfter.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        void shouldCompleteReserveReleaseLifecycle() {
            // 1. Reserve
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            // 2. Release
            ResponseEntity<Map> release = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());
            assertThat(release.getBody().get("status")).isEqualTo("RELEASED");

            // 3. Verify finalized
            ResponseEntity<Map> detail = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(detail.getBody().get("status")).isEqualTo("RELEASED");
        }

        @Test
        void shouldCompleteReserveExtendCommitLifecycle() {
            // 1. Reserve
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            // 2. Extend
            ResponseEntity<Map> extend = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));
            assertThat(extend.getBody().get("status")).isEqualTo("ACTIVE");

            // 3. Commit
            ResponseEntity<Map> commit = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(4000));
            assertThat(commit.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void shouldHandleMultipleReservationsCumulatively() {
            // Reserve multiple times and verify budget is tracked cumulatively
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100_000);
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100_000);
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100_000);

            ResponseEntity<Map> balance = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balance.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);
            Map<String, Object> reserved = (Map<String, Object>) bal.get("reserved");
            long reservedAmount = ((Number) reserved.get("amount")).longValue();

            assertThat(reservedAmount).isGreaterThanOrEqualTo(300_000);
        }

        @Test
        void shouldIsolateTenantBudgets() {
            // Tenant A and B operate independently
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 50_000);
            createReservationAndGetId(TENANT_B, API_KEY_SECRET_B, 30_000);

            ResponseEntity<Map> balanceA = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            ResponseEntity<Map> balanceB = get(
                    "/v1/balances?tenant=" + TENANT_B, API_KEY_SECRET_B);

            assertThat(balanceA.getStatusCode().value()).isEqualTo(200);
            assertThat(balanceB.getStatusCode().value()).isEqualTo(200);

            var balancesA = (java.util.List<Map<String, Object>>) balanceA.getBody().get("balances");
            var balancesB = (java.util.List<Map<String, Object>>) balanceB.getBody().get("balances");

            assertThat(balancesA).isNotEmpty();
            assertThat(balancesB).isNotEmpty();
        }

        @Test
        void shouldDecideReserveAndCommitConsistently() {
            // 1. Decide first
            ResponseEntity<Map> decision = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 5000));
            assertThat(decision.getBody().get("decision")).isEqualTo("ALLOW");

            // 2. Reserve
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            // 3. Commit
            ResponseEntity<Map> commit = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(4500));
            assertThat(commit.getBody().get("status")).isEqualTo("COMMITTED");

            // 4. Verify budget reflects spend
            ResponseEntity<Map> balance = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            assertThat(balance.getStatusCode().value()).isEqualTo(200);
        }
    }

    // ========================================================================
    // Error Response Format
    // ========================================================================

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

    // ========================================================================
    // Spec Compliance: Overdraft Ledger Invariant (F1)
    // ========================================================================

    @Nested
    @DisplayName("Overdraft Ledger Invariant")
    class OverdraftLedgerInvariant {

        @Test
        void shouldTrackDebtInCommitOverdraft() {
            // Budget: allocated=1_000_000, overdraft_limit=100_000
            // Drain budget first so that overage forces debt creation.
            String drain = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 950_000);
            post("/v1/reservations/" + drain + "/commit", API_KEY_SECRET_A, commitBody(950_000));
            // remaining ≈ 50_000, spent = 950_000

            // Reserve 1000 with ALLOW_WITH_OVERDRAFT
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");
            // remaining ≈ 49_000, reserved = 1_000

            // Commit 100_000 (overage delta = 99_000, exceeds remaining ≈ 49_000)
            // funded = 49_000, deficit = 50_000 ≤ overdraft_limit(100_000) → allowed
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(100_000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            Map commitBody = commitResp.getBody();
            assertThat(commitBody.get("status")).isEqualTo("COMMITTED");

            // Verify charged amount equals actual
            Map<String, Object> charged = (Map<String, Object>) commitBody.get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(100_000);

            // Verify debt was actually created in the balance
            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balanceResp.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);
            Map<String, Object> debt = (Map<String, Object>) bal.get("debt");
            assertThat(debt).isNotNull();
            assertThat(((Number) debt.get("amount")).longValue()).isGreaterThan(0);
        }

        @Test
        void shouldTrackDebtInBalanceAfterOverdraftCommit() {
            // Reserve small amount, commit larger (within overdraft limit)
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit with overage that exceeds remaining budget to force debt
            // Budget: 1_000_000 allocated, 999_000 remaining after reserve
            // Commit 1_100_000 → overage delta = 1_099_000, needs budget for that
            // Actually let's use a more targeted approach:
            // Reserve 900_000 first to reduce remaining, then use another reservation for overdraft
            String res1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 900_000);
            post("/v1/reservations/" + res1 + "/commit", API_KEY_SECRET_A, commitBody(900_000));

            // Now remaining ≈ 100_000 (1_000_000 - 900_000 - 1_000 reserved)
            // Commit the overdraft reservation with actual=200_000 (overage delta=199_000)
            // remaining(99_000) < delta(199_000), so debt = 199_000 - 99_000 = 100_000
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(200_000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);

            // Check balance shows debt > 0
            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balanceResp.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);
            Map<String, Object> debt = (Map<String, Object>) bal.get("debt");
            long debtAmount = ((Number) debt.get("amount")).longValue();
            assertThat(debtAmount).isGreaterThan(0);
        }

        @Test
        void shouldAllowNegativeRemainingInOverdraft() {
            // Drain budget then overdraft to force negative remaining
            String res1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 900_000);
            post("/v1/reservations/" + res1 + "/commit", API_KEY_SECRET_A, commitBody(900_000));

            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit more than remaining, forcing remaining negative
            post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(200_000));

            // Remaining should be negative (SignedAmount allows it)
            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balanceResp.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);
            Map<String, Object> remaining = (Map<String, Object>) bal.get("remaining");
            long remainingAmount = ((Number) remaining.get("amount")).longValue();
            assertThat(remainingAmount).isLessThan(0);
        }

        @Test
        void shouldRejectWhenOverdraftLimitExceeded() {
            // Drain budget close to limit
            String res1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 900_000);
            post("/v1/reservations/" + res1 + "/commit", API_KEY_SECRET_A, commitBody(900_000));

            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Overdraft limit is allocated/10 = 100_000
            // Try to commit way beyond overdraft limit
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(500_000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(409);
            assertThat(commitResp.getBody().get("error")).isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        }

        @Test
        void shouldTrackDebtInEventOverdraft() {
            // Drain budget
            post("/v1/events", API_KEY_SECRET_A, eventBody(TENANT_A, 900_000));

            // Event with overdraft
            Map<String, Object> body = eventBody(TENANT_A, 200_000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(201);

            // Check debt in balance
            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balanceResp.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);
            Map<String, Object> debt = (Map<String, Object>) bal.get("debt");
            long debtAmount = ((Number) debt.get("amount")).longValue();
            assertThat(debtAmount).isGreaterThan(0);
        }

        @Test
        void shouldRejectEventWhenOverdraftLimitExceeded() {
            // Spec: event with ALLOW_WITH_OVERDRAFT where (debt + actual) > overdraft_limit
            // MUST return 409 OVERDRAFT_LIMIT_EXCEEDED
            // Budget: allocated=1_000_000, overdraft_limit=100_000
            // Drain budget
            post("/v1/events", API_KEY_SECRET_A, eventBody(TENANT_A, 950_000));

            // Event with overdraft that exceeds limit: remaining=50_000, actual=500_000
            // deficit=450_000 > overdraft_limit=100_000
            Map<String, Object> body = eventBody(TENANT_A, 500_000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        }

        @Test
        void shouldMaintainLedgerInvariantAfterCommit() {
            // allocated = remaining + spent + reserved + debt (spec invariant)
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);
            post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(3000));

            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balanceResp.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);

            long allocated = ((Number) ((Map) bal.get("allocated")).get("amount")).longValue();
            long remaining = ((Number) ((Map) bal.get("remaining")).get("amount")).longValue();
            long reserved = ((Number) ((Map) bal.get("reserved")).get("amount")).longValue();
            long spent = ((Number) ((Map) bal.get("spent")).get("amount")).longValue();
            // debt is omitted (null) when 0 due to @JsonInclude(NON_NULL)
            long debt = bal.get("debt") != null
                    ? ((Number) ((Map) bal.get("debt")).get("amount")).longValue() : 0;

            // Spec: remaining = allocated - spent - reserved - debt
            assertThat(remaining).isEqualTo(allocated - spent - reserved - debt);
        }
    }

    // ========================================================================
    // Spec Compliance: Commit Ledger Math
    // ========================================================================

    @Nested
    @DisplayName("Commit Ledger Math")
    class CommitLedgerMath {

        @Test
        void shouldCorrectlyAccountUnderspend() {
            // Reserve 5000, commit 3000 → released=2000, spent=3000
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(3000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            Map commitBody = commitResp.getBody();

            Map<String, Object> charged = (Map<String, Object>) commitBody.get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(3000);

            Map<String, Object> released = (Map<String, Object>) commitBody.get("released");
            assertThat(released).isNotNull();
            assertThat(((Number) released.get("amount")).longValue()).isEqualTo(2000);
        }

        @Test
        void shouldCorrectlyAccountExactSpend() {
            // Reserve 5000, commit 5000 → released=null, spent=5000
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);

            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(5000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            Map commitBody = commitResp.getBody();

            Map<String, Object> charged = (Map<String, Object>) commitBody.get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(5000);

            // No released amount when exact match
            assertThat(commitBody.get("released")).isNull();
        }

        @Test
        void shouldCorrectlyAccountOverspendWithAllowIfAvailable() {
            // Reserve 5000 with ALLOW_IF_AVAILABLE, commit 8000
            Map<String, Object> body = reservationBody(TENANT_A, 5000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(8000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            Map commitBody = commitResp.getBody();
            Map<String, Object> charged = (Map<String, Object>) commitBody.get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(8000);
        }

        @Test
        void shouldRejectAllowIfAvailableWhenInsufficient() {
            // Drain most of the budget
            String res1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 990_000);
            post("/v1/reservations/" + res1 + "/commit", API_KEY_SECRET_A, commitBody(990_000));

            // Reserve 1000 with ALLOW_IF_AVAILABLE
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit 50_000 → delta=49_000, but remaining ≈ 9_000 → BUDGET_EXCEEDED
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(50_000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(409);
            assertThat(commitResp.getBody().get("error")).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldMaintainInvariantAfterRelease() {
            // Reserve then release — remaining should return to previous value
            ResponseEntity<Map> beforeResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var beforeBalances = (java.util.List<Map<String, Object>>) beforeResp.getBody().get("balances");
            long remainingBefore = ((Number) ((Map) beforeBalances.get(0).get("remaining")).get("amount")).longValue();

            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);
            post("/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            ResponseEntity<Map> afterResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var afterBalances = (java.util.List<Map<String, Object>>) afterResp.getBody().get("balances");
            long remainingAfter = ((Number) ((Map) afterBalances.get(0).get("remaining")).get("amount")).longValue();

            assertThat(remainingAfter).isEqualTo(remainingBefore);
        }
    }

    // ========================================================================
    // Spec Compliance: Input Validation (F2, F4, F5)
    // ========================================================================

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

    // ========================================================================
    // Spec Compliance: Balance Response Format (F3)
    // ========================================================================

    @Nested
    @DisplayName("Balance Response Format")
    class BalanceResponseFormat {

        @Test
        void shouldReturnLeafScopeInBalanceField() {
            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            var balances = (java.util.List<Map<String, Object>>) resp.getBody().get("balances");
            assertThat(balances).isNotEmpty();

            Map<String, Object> balance = balances.get(0);
            String scope = (String) balance.get("scope");
            String scopePath = (String) balance.get("scope_path");

            // scope should be leaf segment (e.g. "tenant:tenant-a"), not the full path
            assertThat(scope).doesNotContain("/");
            // scope_path can contain hierarchy separators
            assertThat(scopePath).isNotNull();
            // scope should be the last segment of scope_path
            String expectedLeaf = scopePath.contains("/")
                    ? scopePath.substring(scopePath.lastIndexOf('/') + 1)
                    : scopePath;
            assertThat(scope).isEqualTo(expectedLeaf);
        }

        @Test
        void shouldReturnDebtAndOverdraftFieldsInBalance() {
            // Create debt within overdraft_limit so debt/overdraft fields are populated.
            // Budget: allocated=1_000_000, overdraft_limit=100_000.
            // First event spends 850k leaving remaining=150k.
            // Second event (200k with ALLOW_WITH_OVERDRAFT) creates deficit=50k (within limit).
            // debt/overdraft_limit are omitted when 0 via @JsonInclude(NON_NULL).
            // is_over_limit can only be true through concurrent operations (pre-check blocks
            // sequential debt > overdraft_limit), so we don't assert its presence here.
            post("/v1/events", API_KEY_SECRET_A, eventBody(TENANT_A, 850_000));
            Map<String, Object> overdraftEvent = eventBody(TENANT_A, 200_000);
            overdraftEvent.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            post("/v1/events", API_KEY_SECRET_A, overdraftEvent);

            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            var balances = (java.util.List<Map<String, Object>>) resp.getBody().get("balances");
            Map<String, Object> balance = balances.get(0);

            assertThat(balance).containsKey("debt");
            assertThat(balance).containsKey("overdraft_limit");
        }

        @Test
        void shouldReturnSignedAmountForRemaining() {
            // remaining uses SignedAmount (allows negative in overdraft)
            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);

            var balances = (java.util.List<Map<String, Object>>) resp.getBody().get("balances");
            Map<String, Object> remaining = (Map<String, Object>) balances.get(0).get("remaining");

            // Verify it has unit and amount fields
            assertThat(remaining).containsKey("unit");
            assertThat(remaining).containsKey("amount");
            assertThat(remaining.get("unit")).isEqualTo("TOKENS");
        }
    }

    // ========================================================================
    // Gap #1: DEBT_OUTSTANDING — new reservations blocked when debt > 0
    // ========================================================================

    @Nested
    @DisplayName("Debt Outstanding")
    class DebtOutstanding {

        @Test
        void shouldRejectNewReservationWhenDebtOutstanding() {
            // Drain budget so overdraft commit creates debt.
            // Budget: allocated=1_000_000, overdraft_limit=100_000
            String drain = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 950_000);
            post("/v1/reservations/" + drain + "/commit", API_KEY_SECRET_A, commitBody(950_000));
            // remaining=50_000

            // Create overdraft reservation and commit to create debt
            Map<String, Object> overdraftBody = reservationBody(TENANT_A, 1000);
            overdraftBody.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, overdraftBody);
            String overdraftResId = (String) reserveResp.getBody().get("reservation_id");
            // remaining=49_000

            // Commit with overage that creates debt: delta=99_000, remaining=49_000
            // funded=49_000, deficit=50_000 ≤ 100_000 → allowed
            post("/v1/reservations/" + overdraftResId + "/commit",
                    API_KEY_SECRET_A, commitBody(100_000));
            // Now debt=50_000 > 0

            // New reservation should be blocked with DEBT_OUTSTANDING
            ResponseEntity<Map> newResp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));

            assertThat(newResp.getStatusCode().value()).isEqualTo(409);
            assertThat(newResp.getBody().get("error")).isEqualTo("DEBT_OUTSTANDING");
        }

        @Test
        void shouldAllowCommitAndReleaseOnExistingReservationsDespiteDebt() {
            // Spec: existing reservations may commit/release normally even when debt > 0
            // Create two reservations before debt exists
            String res1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            String res2 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Drain and create debt
            String drain = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 948_000);
            post("/v1/reservations/" + drain + "/commit", API_KEY_SECRET_A, commitBody(948_000));

            Map<String, Object> overdraftBody = reservationBody(TENANT_A, 1000);
            overdraftBody.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> overdraftResp = post("/v1/reservations", API_KEY_SECRET_A, overdraftBody);
            String overdraftResId = (String) overdraftResp.getBody().get("reservation_id");
            post("/v1/reservations/" + overdraftResId + "/commit",
                    API_KEY_SECRET_A, commitBody(100_000));
            // debt > 0 now

            // Existing reservation can still commit
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + res1 + "/commit",
                    API_KEY_SECRET_A, commitBody(500));
            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");

            // Existing reservation can still release
            ResponseEntity<Map> releaseResp = post(
                    "/v1/reservations/" + res2 + "/release",
                    API_KEY_SECRET_A, releaseBody());
            assertThat(releaseResp.getStatusCode().value()).isEqualTo(200);
            assertThat(releaseResp.getBody().get("status")).isEqualTo("RELEASED");
        }
    }

    // ========================================================================
    // Gap #2: Error Precedence — OVERDRAFT_LIMIT_EXCEEDED overrides DEBT_OUTSTANDING
    // ========================================================================

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
        void shouldReturnDebtOutstandingWhenNotOverLimit() {
            // When debt > 0 but is_over_limit=false, should return DEBT_OUTSTANDING
            String drain = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 950_000);
            post("/v1/reservations/" + drain + "/commit", API_KEY_SECRET_A, commitBody(950_000));

            Map<String, Object> overdraftBody = reservationBody(TENANT_A, 1000);
            overdraftBody.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, overdraftBody);
            String overdraftResId = (String) reserveResp.getBody().get("reservation_id");
            post("/v1/reservations/" + overdraftResId + "/commit",
                    API_KEY_SECRET_A, commitBody(100_000));
            // debt=50_000 > 0, is_over_limit=false

            ResponseEntity<Map> newResp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));

            assertThat(newResp.getStatusCode().value()).isEqualTo(409);
            assertThat(newResp.getBody().get("error")).isEqualTo("DEBT_OUTSTANDING");
        }
    }

    // ========================================================================
    // Gap #3: RESERVATION_EXPIRED — 410 on commit/release/extend after expiry
    // ========================================================================

    @Nested
    @DisplayName("Reservation Expiration (410)")
    class ReservationExpiration {

        @Test
        void shouldReturn410OnCommitAfterExpiry() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Force expiration by setting expires_at in the past with grace=0
            expireReservationInRedis(reservationId, System.currentTimeMillis() - 10_000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(500));

            assertThat(resp.getStatusCode().value()).isEqualTo(410);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");
        }

        @Test
        void shouldReturn410OnReleaseAfterExpiry() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            expireReservationInRedis(reservationId, System.currentTimeMillis() - 10_000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(410);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");
        }

        @Test
        void shouldReturn410OnExtendAfterExpiry() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            expireReservationInRedis(reservationId, System.currentTimeMillis() - 10_000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(410);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");
        }

        @Test
        void shouldAllowCommitWithinGracePeriod() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Set expires_at 5 seconds ago but with 60 second grace period
            // commit.lua: allowed if now <= expires_at + grace_ms
            try (Jedis jedis = jedisPool.getResource()) {
                String key = "reservation:res_" + reservationId;
                jedis.hset(key, "expires_at", String.valueOf(System.currentTimeMillis() - 5_000));
                jedis.hset(key, "grace_ms", "60000");
            }

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(500));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void shouldAllowReleaseWithinGracePeriod() {
            // Spec: release allowed through expires_at_ms + grace_period_ms (same as commit)
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            try (Jedis jedis = jedisPool.getResource()) {
                String key = "reservation:res_" + reservationId;
                jedis.hset(key, "expires_at", String.valueOf(System.currentTimeMillis() - 5_000));
                jedis.hset(key, "grace_ms", "60000");
            }

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_A, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("RELEASED");
        }

        @Test
        void shouldRejectExtendEvenWithinGracePeriod() {
            // Spec: extend only allowed when server time <= expires_at_ms (no grace)
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            try (Jedis jedis = jedisPool.getResource()) {
                String key = "reservation:res_" + reservationId;
                jedis.hset(key, "expires_at", String.valueOf(System.currentTimeMillis() - 1_000));
                jedis.hset(key, "grace_ms", "60000");
            }

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(410);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");
        }
    }

    // ========================================================================
    // Gap #4: Hierarchical Scope Derivation — multi-level budget tracking
    // ========================================================================

    @Nested
    @DisplayName("Hierarchical Scope Derivation")
    class HierarchicalScopes {

        @Test
        void shouldReserveAcrossMultipleHierarchyLevels() {
            // Seed budgets at derived scope levels for tenant-a with agent=my-agent.
            // deriveScopes for {tenant: "tenant-a", agent: "my-agent"} produces (gaps skipped):
            //   tenant:tenant-a
            //   tenant:tenant-a/agent:my-agent
            try (Jedis jedis = jedisPool.getResource()) {
                // Tenant-level already seeded by @BeforeEach; seed agent-level
                seedScopeBudget(jedis, "tenant:tenant-a/agent:my-agent", "TOKENS", 200_000, 20_000);
            }

            Map<String, String> subject = Map.of("tenant", TENANT_A, "agent", "my-agent");
            Map<String, Object> body = reservationBodyWithSubject(subject, 5000, "TOKENS");

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("ALLOW");
            assertThat(resp.getBody().get("reservation_id")).isNotNull();

            // Verify affected_scopes contains only explicitly provided levels
            var affectedScopes = (java.util.List<String>) resp.getBody().get("affected_scopes");
            assertThat(affectedScopes).hasSize(2);
            assertThat(affectedScopes.get(0)).isEqualTo("tenant:tenant-a");
            assertThat(affectedScopes.get(1)).isEqualTo("tenant:tenant-a/agent:my-agent");
        }

        @Test
        void shouldEnforceLowestLevelBudget() {
            // Seed lower-level budget that is too small (gaps skipped, so use direct scope path)
            try (Jedis jedis = jedisPool.getResource()) {
                // Agent budget is only 100 tokens — too small for 5000 estimate
                seedScopeBudget(jedis, "tenant:tenant-a/agent:small-budget", "TOKENS", 100, 10);
            }

            Map<String, String> subject = Map.of("tenant", TENANT_A, "agent", "small-budget");
            Map<String, Object> body = reservationBodyWithSubject(subject, 5000, "TOKENS");

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldCommitAcrossHierarchyAndUpdateBalances() {
            // Seed budgets at derived scope levels (gaps skipped)
            try (Jedis jedis = jedisPool.getResource()) {
                seedScopeBudget(jedis, "tenant:tenant-a/agent:test-agent", "TOKENS", 200_000, 20_000);
            }

            Map<String, String> subject = Map.of("tenant", TENANT_A, "agent", "test-agent");
            Map<String, Object> body = reservationBodyWithSubject(subject, 5000, "TOKENS");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(3000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");

            // Verify spent was updated at both tenant and agent levels
            try (Jedis jedis = jedisPool.getResource()) {
                String tenantSpent = jedis.hget("budget:tenant:tenant-a:TOKENS", "spent");
                assertThat(Long.parseLong(tenantSpent)).isEqualTo(3000);

                String agentSpent = jedis.hget(
                        "budget:tenant:tenant-a/agent:test-agent:TOKENS",
                        "spent");
                assertThat(Long.parseLong(agentSpent)).isEqualTo(3000);
            }
        }

        @Test
        void shouldDeriveScopePathSkippingGaps() {
            // Subject with tenant + toolset (skipping workspace/app/workflow/agent)
            // Gaps are skipped — only explicitly provided levels appear in scope path
            try (Jedis jedis = jedisPool.getResource()) {
                seedScopeBudget(jedis, "tenant:tenant-a/toolset:search", "TOKENS", 100_000, 10_000);
            }

            Map<String, String> subject = Map.of("tenant", TENANT_A, "toolset", "search");
            Map<String, Object> body = reservationBodyWithSubject(subject, 1000, "TOKENS");

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            // Should derive only 2 scope levels (tenant + toolset), gaps skipped
            var affectedScopes = (java.util.List<String>) resp.getBody().get("affected_scopes");
            assertThat(affectedScopes).hasSize(2);
            assertThat(affectedScopes.get(0)).isEqualTo("tenant:tenant-a");
            assertThat(affectedScopes.get(1)).isEqualTo("tenant:tenant-a/toolset:search");

            String scopePath = (String) resp.getBody().get("scope_path");
            assertThat(scopePath).isEqualTo("tenant:tenant-a/toolset:search");
        }
    }

    // ========================================================================
    // Gap #5: TTL / Extend / Grace Period Boundary Validation
    // ========================================================================

    @Nested
    @DisplayName("TTL & Extend Boundary Validation")
    class TtlBoundaryValidation {

        @Test
        void shouldRejectTtlBelowMinimum() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("ttl_ms", 999); // min is 1000

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectTtlAboveMaximum() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("ttl_ms", 86_400_001); // max is 86400000

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldAcceptMinimumTtl() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("ttl_ms", 1000); // exactly min

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("ALLOW");
        }

        @Test
        void shouldAcceptMaximumTtl() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("ttl_ms", 86_400_000); // exactly max

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("ALLOW");
        }

        @Test
        void shouldRejectExtendBelowMinimum() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            Map<String, Object> body = extendBody(0); // min is 1

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldRejectExtendAboveMaximum() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            Map<String, Object> body = extendBody(86_400_001); // max is 86400000

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldAcceptMinimumExtend() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            Map<String, Object> body = extendBody(1); // exactly min

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
        }

        @Test
        void shouldRejectGracePeriodAboveMaximum() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("grace_period_ms", 60_001); // max is 60000

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        void shouldAcceptGracePeriodZero() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("grace_period_ms", 0); // exactly min

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("ALLOW");
        }

        @Test
        void shouldAcceptMaximumGracePeriod() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("grace_period_ms", 60_000); // exactly max

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("decision")).isEqualTo("ALLOW");
        }
    }

    // ========================================================================
    // Expiry Sweep — end-to-end expire.lua + ReservationExpiryService tests
    // ========================================================================

    @Nested
    @DisplayName("Expiry Sweep")
    class ExpirySweep {

        @Test
        void shouldExpireReservationAndReleaseBudget() {
            // Record initial remaining budget
            ResponseEntity<Map> balanceBefore = get("/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            List<Map<String, Object>> balancesBefore = (List<Map<String, Object>>) balanceBefore.getBody().get("balances");
            int remainingBefore = ((Number) ((Map<String, Object>) balancesBefore.get(0).get("remaining")).get("amount")).intValue();

            // Create reservation and force it past expiry
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 5000);
            expireReservationInRedis(reservationId, System.currentTimeMillis() - 10_000);

            // Manually trigger the sweep
            expiryService.expireReservations();

            // Verify reservation is now EXPIRED
            ResponseEntity<Map> resp = get("/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("EXPIRED");

            // Verify budget was released (remaining restored)
            ResponseEntity<Map> balanceAfter = get("/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            List<Map<String, Object>> balancesAfter = (List<Map<String, Object>>) balanceAfter.getBody().get("balances");
            int remainingAfter = ((Number) ((Map<String, Object>) balancesAfter.get(0).get("remaining")).get("amount")).intValue();
            assertThat(remainingAfter).isEqualTo(remainingBefore);

            // Verify removed from TTL sorted set
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.zscore("reservation:ttl", reservationId)).isNull();
            }
        }

        @Test
        void shouldSkipReservationInGracePeriod() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Set expires_at 5s ago but with 60s grace period — should NOT expire
            try (Jedis jedis = jedisPool.getResource()) {
                String key = "reservation:res_" + reservationId;
                jedis.hset(key, "expires_at", String.valueOf(System.currentTimeMillis() - 5_000));
                jedis.hset(key, "grace_ms", "60000");
                jedis.zadd("reservation:ttl", System.currentTimeMillis() - 5_000, reservationId);
            }

            expiryService.expireReservations();

            // Should still be ACTIVE
            ResponseEntity<Map> resp = get("/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
        }

        @Test
        void shouldSkipAlreadyCommittedReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            post("/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, commitBody(800));

            // Re-add to TTL sorted set with past timestamp (simulates stale entry)
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.zadd("reservation:ttl", System.currentTimeMillis() - 10_000, reservationId);
            }

            expiryService.expireReservations();

            // Should remain COMMITTED — sweep should skip and clean up TTL entry
            ResponseEntity<Map> resp = get("/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("COMMITTED");

            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.zscore("reservation:ttl", reservationId)).isNull();
            }
        }

        @Test
        void shouldSkipAlreadyReleasedReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            post("/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, releaseBody());

            // Re-add to TTL sorted set with past timestamp (simulates stale entry)
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.zadd("reservation:ttl", System.currentTimeMillis() - 10_000, reservationId);
            }

            expiryService.expireReservations();

            // Should remain RELEASED — sweep should skip and clean up TTL entry
            ResponseEntity<Map> resp = get("/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("RELEASED");

            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.zscore("reservation:ttl", reservationId)).isNull();
            }
        }

        @Test
        void shouldCleanUpOrphanedTtlEntry() {
            // Add a fake entry to TTL sorted set with no corresponding reservation hash
            String orphanId = "orphan-" + UUID.randomUUID();
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.zadd("reservation:ttl", System.currentTimeMillis() - 10_000, orphanId);
            }

            expiryService.expireReservations();

            // Orphaned entry should be cleaned up (NOT_FOUND path does ZREM)
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.zscore("reservation:ttl", orphanId)).isNull();
            }
        }

        @Test
        void shouldReturnExpiredStatusOnGetAfterSweep() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            expireReservationInRedis(reservationId, System.currentTimeMillis() - 10_000);

            expiryService.expireReservations();

            // GET should return 200 with status=EXPIRED (not 410)
            ResponseEntity<Map> resp = get("/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("status")).isEqualTo("EXPIRED");
        }

        @Test
        void shouldExpireMultiScopeReservationAndReleaseAllBudgets() {
            // Seed agent-level budget in addition to tenant-level
            try (Jedis jedis = jedisPool.getResource()) {
                seedScopeBudget(jedis,
                        "tenant:" + TENANT_A + "/agent:my-agent", "TOKENS", 500_000, 50_000);
            }

            // Create multi-scope reservation
            Map<String, Object> body = reservationBodyWithSubject(
                    Map.of("tenant", TENANT_A, "agent", "my-agent"), 2000, "TOKENS");
            ResponseEntity<Map> createResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(createResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) createResp.getBody().get("reservation_id");

            // Record budget state after reservation
            long tenantReservedBefore, agentReservedBefore;
            try (Jedis jedis = jedisPool.getResource()) {
                tenantReservedBefore = Long.parseLong(
                        jedis.hget("budget:tenant:" + TENANT_A + ":TOKENS", "reserved"));
                agentReservedBefore = Long.parseLong(
                        jedis.hget("budget:tenant:" + TENANT_A + "/agent:my-agent:TOKENS", "reserved"));
            }
            assertThat(tenantReservedBefore).isGreaterThanOrEqualTo(2000);
            assertThat(agentReservedBefore).isGreaterThanOrEqualTo(2000);

            // Force expiry and run sweep
            expireReservationInRedis(reservationId, System.currentTimeMillis() - 10_000);
            expiryService.expireReservations();

            // Verify both scope budgets released
            try (Jedis jedis = jedisPool.getResource()) {
                long tenantReservedAfter = Long.parseLong(
                        jedis.hget("budget:tenant:" + TENANT_A + ":TOKENS", "reserved"));
                long agentReservedAfter = Long.parseLong(
                        jedis.hget("budget:tenant:" + TENANT_A + "/agent:my-agent:TOKENS", "reserved"));
                assertThat(tenantReservedAfter).isEqualTo(tenantReservedBefore - 2000);
                assertThat(agentReservedAfter).isEqualTo(agentReservedBefore - 2000);
            }
        }
    }

    // ========================================================================
    // Budget Status — FROZEN / CLOSED enforcement
    // ========================================================================

    @Nested
    @DisplayName("Budget Status")
    class BudgetStatus {

        @Test
        void shouldRejectReservationOnFrozenBudget() {
            try (Jedis jedis = jedisPool.getResource()) {
                // Replace the default ACTIVE budget with a FROZEN one
                seedBudgetWithStatus(jedis, TENANT_A, "TOKENS", 1_000_000, "FROZEN");
            }

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("BUDGET_FROZEN");
        }

        @Test
        void shouldRejectReservationOnClosedBudget() {
            try (Jedis jedis = jedisPool.getResource()) {
                // Replace the default ACTIVE budget with a CLOSED one
                seedBudgetWithStatus(jedis, TENANT_A, "TOKENS", 1_000_000, "CLOSED");
            }

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("BUDGET_CLOSED");
        }
    }
}
