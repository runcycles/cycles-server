package io.runcycles.protocol.api;

import io.runcycles.protocol.data.service.ReservationExpiryService;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@DisplayName("Commit Ledger Math Integration Tests")
class CommitLedgerMathIntegrationTest extends BaseIntegrationTest {

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
        void shouldCapAllowIfAvailableWhenInsufficient() {
            // Drain most of the budget
            String res1 = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 990_000);
            post("/v1/reservations/" + res1 + "/commit", API_KEY_SECRET_A, commitBody(990_000));

            // Reserve 1000 with ALLOW_IF_AVAILABLE
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit 50_000 → delta=49_000, but remaining ≈ 9_000 after reservation hold
            // Delta capped to ~9_000, charged = 1000 + ~9000 = ~10_000 (not full 50_000)
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(50_000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");
            Map<String, Object> charged = (Map<String, Object>) commitResp.getBody().get("charged");
            // Charged should be much less than 50_000 (estimate + available remaining)
            assertThat(((Number) charged.get("amount")).longValue()).isLessThan(50_000);
            assertThat(((Number) charged.get("amount")).longValue()).isGreaterThanOrEqualTo(1000);

            // Scope should be marked is_over_limit
            List<Map<String, Object>> balances = (List<Map<String, Object>>) commitResp.getBody().get("balances");
            assertThat(balances).isNotEmpty();
            assertThat(balances.get(0).get("is_over_limit")).isEqualTo(true);
        }

        @Test
        void shouldAllowIfAvailableWithExactMatch() {
            // delta=0: actual == estimate → normal commit, no capping, no is_over_limit
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(1000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> charged = (Map<String, Object>) commitResp.getBody().get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(1000);
        }

        @Test
        void shouldAllowIfAvailableWithUnderspend() {
            // actual < estimate → release unused, no capping, no is_over_limit
            Map<String, Object> body = reservationBody(TENANT_A, 5000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(3000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> charged = (Map<String, Object>) commitResp.getBody().get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(3000);
            // Released = estimate - actual = 2000
            Map<String, Object> released = (Map<String, Object>) commitResp.getBody().get("released");
            assertThat(released).isNotNull();
            assertThat(((Number) released.get("amount")).longValue()).isEqualTo(2000);
        }

        @Test
        void shouldAllowIfAvailableWithZeroRemainingChargesEstimateOnly() {
            // Drain budget so remaining = estimate exactly, then commit > estimate
            // After reserve: remaining=0, so capped_delta=0, charged=estimate
            String drainId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 995_000);
            post("/v1/reservations/" + drainId + "/commit", API_KEY_SECRET_A, commitBody(995_000));

            // remaining ≈ 5000, reserve 5000 → remaining after reserve = 0
            Map<String, Object> body = reservationBody(TENANT_A, 5000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit 6000 (delta=1000) but remaining=0 → capped_delta=0, charged=5000
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(6000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> charged = (Map<String, Object>) commitResp.getBody().get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(5000);

            List<Map<String, Object>> balances = (List<Map<String, Object>>) commitResp.getBody().get("balances");
            assertThat(balances).isNotEmpty();
            assertThat(balances.get(0).get("is_over_limit")).isEqualTo(true);
        }

        @Test
        void shouldBlockReservationAfterCappedAllowIfAvailableCommit() {
            // After a capped ALLOW_IF_AVAILABLE commit sets is_over_limit, next reservation should be DENIED
            String drainId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 999_000);
            post("/v1/reservations/" + drainId + "/commit", API_KEY_SECRET_A, commitBody(999_000));

            // Reserve last 1000, commit 2000 → capped, is_over_limit=true
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");
            post("/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, commitBody(2000));

            // Now try another reservation — should be blocked by is_over_limit
            Map<String, Object> body2 = reservationBody(TENANT_A, 100);
            ResponseEntity<Map> blockedResp = post("/v1/reservations", API_KEY_SECRET_A, body2);
            assertThat(blockedResp.getStatusCode().value()).isEqualTo(409);
            assertThat(blockedResp.getBody().get("error")).isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        }

        @Test
        void shouldCapEventWithAllowIfAvailableWhenBudgetInsufficient() {
            // Drain most of budget
            String drainId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 999_000);
            post("/v1/reservations/" + drainId + "/commit", API_KEY_SECRET_A, commitBody(999_000));

            // Event with ALLOW_IF_AVAILABLE for amount > remaining
            Map<String, Object> body = eventBody(TENANT_A, 5000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> eventResp = post("/v1/events", API_KEY_SECRET_A, body);
            assertThat(eventResp.getStatusCode().value()).isEqualTo(201);
            assertThat(eventResp.getBody().get("status")).isEqualTo("APPLIED");

            // Verify is_over_limit is set in balances
            List<Map<String, Object>> balances = (List<Map<String, Object>>) eventResp.getBody().get("balances");
            assertThat(balances).isNotEmpty();
            assertThat(balances.get(0).get("is_over_limit")).isEqualTo(true);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ALLOW_IF_AVAILABLE", "ALLOW_WITH_OVERDRAFT"})
        void cappedCommitMarksOnlyScopesThatCannotCoverFullDelta(String policy) {
            String parentScope = "tenant:" + TENANT_A;
            String childScope = parentScope + "/workspace:roomy";
            try (Jedis jedis = jedisPool.getResource()) {
                seedScopeBudget(jedis, parentScope, "TOKENS", 150, 0);
                seedScopeBudget(jedis, childScope, "TOKENS", 1_000, 0);
            }

            Map<String, Object> reserve = reservationBodyWithSubject(
                    Map.of("tenant", TENANT_A, "workspace", "roomy"), 100, "TOKENS");
            reserve.put("overage_policy", policy);
            ResponseEntity<Map> reserved = post("/v1/reservations", API_KEY_SECRET_A, reserve);
            assertThat(reserved.getStatusCode().value()).isEqualTo(200);

            ResponseEntity<Map> committed = post(
                    "/v1/reservations/" + reserved.getBody().get("reservation_id") + "/commit",
                    API_KEY_SECRET_A, commitBody(200));

            assertThat(committed.getStatusCode().value()).isEqualTo(200);
            assertThat(((Map<?, ?>) committed.getBody().get("charged")).get("amount"))
                    .isEqualTo(150);
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.hget("budget:" + parentScope + ":TOKENS", "is_over_limit"))
                        .isEqualTo("true");
                assertThat(jedis.hget("budget:" + childScope + ":TOKENS", "is_over_limit"))
                        .isEqualTo("false");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"ALLOW_IF_AVAILABLE", "ALLOW_WITH_OVERDRAFT"})
        void cappedEventMarksOnlyScopesThatCannotCoverFullAmount(String policy) {
            String parentScope = "tenant:" + TENANT_A;
            String childScope = parentScope + "/workspace:roomy";
            try (Jedis jedis = jedisPool.getResource()) {
                seedScopeBudget(jedis, parentScope, "TOKENS", 50, 0);
                seedScopeBudget(jedis, childScope, "TOKENS", 950, 0);
            }

            Map<String, Object> event = eventBody(TENANT_A, 100);
            event.put("subject", Map.of("tenant", TENANT_A, "workspace", "roomy"));
            event.put("overage_policy", policy);
            ResponseEntity<Map> applied = post("/v1/events", API_KEY_SECRET_A, event);

            assertThat(applied.getStatusCode().value()).isEqualTo(201);
            assertThat(((Map<?, ?>) applied.getBody().get("charged")).get("amount"))
                    .isEqualTo(50);
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.hget("budget:" + parentScope + ":TOKENS", "is_over_limit"))
                        .isEqualTo("true");
                assertThat(jedis.hget("budget:" + childScope + ":TOKENS", "is_over_limit"))
                        .isEqualTo("false");
            }
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
}
