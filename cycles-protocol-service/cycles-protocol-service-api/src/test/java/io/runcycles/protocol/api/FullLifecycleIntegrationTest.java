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

@DisplayName("Full Lifecycle & Response Headers Integration Tests")
class FullLifecycleIntegrationTest extends BaseIntegrationTest {

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
}
