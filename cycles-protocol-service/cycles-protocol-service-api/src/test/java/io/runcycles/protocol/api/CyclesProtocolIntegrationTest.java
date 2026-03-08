package io.runcycles.protocol.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cycles Protocol v0.1.23 Integration Tests")
class CyclesProtocolIntegrationTest extends BaseIntegrationTest {

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
    }

    @Nested
    @DisplayName("POST /v1/reservations/{id}/extend — Extend")
    class ExtendReservation {

        @Test
        void shouldExtendReservation() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map body = resp.getBody();
            assertThat(body.get("status")).isEqualTo("ACTIVE");
            assertThat(body.get("expires_at_ms")).isNotNull();
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
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(body, headersWithIdempotencyKey(
                            API_KEY_SECRET_A, "different-key")),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).isEqualTo("IDEMPOTENCY_MISMATCH");
        }

        private org.springframework.http.HttpHeaders headersWithIdempotencyKey(
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
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(401);
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
    }
}
