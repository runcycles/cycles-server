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

@DisplayName("Overdraft & Debt Integration Tests")
class OverdraftIntegrationTest extends BaseIntegrationTest {

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

        @Test
        void shouldAllowOverageWithAllowIfAvailableWhenBudgetSufficient() {
            // Budget has ~1M allocated; delta of 1000 easily fits in remaining
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit 2000 (overage delta = 1000)
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");
            Map<String, Object> charged = (Map<String, Object>) commitResp.getBody().get("charged");
            assertThat(((Number) charged.get("amount")).longValue()).isEqualTo(2000);
        }

        @Test
        void shouldCapOverageWithAllowIfAvailableWhenBudgetInsufficient() {
            // Drain budget: commit 999_000 so remaining ≈ 1000
            String drainId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 999_000);
            post("/v1/reservations/" + drainId + "/commit", API_KEY_SECRET_A, commitBody(999_000));

            // Reserve 500 with ALLOW_IF_AVAILABLE (fits in ~1000 remaining)
            Map<String, Object> body = reservationBody(TENANT_A, 500);
            body.put("overage_policy", "ALLOW_IF_AVAILABLE");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit 1500 (delta = 1000) but remaining ≈ 500 after reservation hold
            // Delta is capped to available remaining (~500), charged = 500 + 500 = 1000
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(1500));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");
            Map<String, Object> charged = (Map<String, Object>) commitResp.getBody().get("charged");
            // Charged = estimate(500) + capped_delta(~500) = ~1000 (not full 1500)
            assertThat(((Number) charged.get("amount")).longValue()).isLessThan(1500);

            // Scope should be marked is_over_limit since full delta couldn't be covered
            List<Map<String, Object>> balances = (List<Map<String, Object>>) commitResp.getBody().get("balances");
            assertThat(balances).isNotEmpty();
            assertThat(balances.get(0).get("is_over_limit")).isEqualTo(true);
        }
    }

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
        void shouldFallbackToAllowIfAvailableWhenOverdraftLimitZeroOnCommit() {
            // Spec: "If overdraft_limit is absent or 0, no overdraft is permitted
            // (behaves as ALLOW_IF_AVAILABLE)."
            // ALLOW_IF_AVAILABLE: "cap delta to available remaining (minimum across
            // all affected scopes, floor 0), charge estimate + capped_delta, and set
            // is_over_limit=true on scopes where the full delta could not be covered."
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "overdraft_limit", "0");
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "remaining", "50000");
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "spent", "950000");
            }

            // Reserve 1000, then commit 100_000 (overage delta=99_000 > remaining=49_000)
            // With overdraft_limit=0, should cap like ALLOW_IF_AVAILABLE, not reject.
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            String resId = (String) reserveResp.getBody().get("reservation_id");

            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + resId + "/commit",
                    API_KEY_SECRET_A, commitBody(100_000));

            // Spec: commit always succeeds with ALLOW_IF_AVAILABLE fallback (never rejects)
            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            // Spec: "charged = estimate + capped_delta" — must be less than actual
            Map<String, Object> charged = (Map<String, Object>) commitResp.getBody().get("charged");
            long chargedAmount = ((Number) charged.get("amount")).longValue();
            assertThat(chargedAmount).isLessThan(100_000);
            assertThat(chargedAmount).isGreaterThan(0);

            // Spec: "set is_over_limit=true on scopes where full delta could not be covered"
            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balanceResp.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);
            assertThat(bal.get("is_over_limit")).isEqualTo(true);

            // Spec: ALLOW_IF_AVAILABLE "never creates debt"
            Map<String, Object> debt = (Map<String, Object>) bal.get("debt");
            long debtAmount = debt != null ? ((Number) debt.get("amount")).longValue() : 0;
            assertThat(debtAmount).isEqualTo(0);
        }

        @Test
        void shouldFallbackToAllowIfAvailableWhenOverdraftLimitZeroOnEvent() {
            // Spec: "If overdraft_limit is absent or 0, no overdraft is permitted
            // (behaves as ALLOW_IF_AVAILABLE)."
            // ALLOW_IF_AVAILABLE for events: "cap the charge to available remaining ...
            // and set is_over_limit=true on scopes where the full amount could not be covered."
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "overdraft_limit", "0");
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "remaining", "50000");
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "spent", "950000");
            }

            // Event for 500_000 (exceeds remaining 50_000)
            Map<String, Object> body = eventBody(TENANT_A, 500_000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);

            // Spec: event always succeeds (never rejects with ALLOW_IF_AVAILABLE)
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            // Spec: "charged field present when ... caps the event charge to remaining budget"
            assertThat(resp.getBody().get("charged")).isNotNull();
            Map<String, Object> charged = (Map<String, Object>) resp.getBody().get("charged");
            long chargedAmount = ((Number) charged.get("amount")).longValue();
            assertThat(chargedAmount).isEqualTo(50_000);

            // Spec: "set is_over_limit=true on scopes where full amount could not be covered"
            ResponseEntity<Map> balanceResp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);
            var balances = (java.util.List<Map<String, Object>>) balanceResp.getBody().get("balances");
            Map<String, Object> bal = balances.get(0);
            assertThat(bal.get("is_over_limit")).isEqualTo(true);

            // Spec: ALLOW_IF_AVAILABLE "never creates debt"
            Map<String, Object> debt = (Map<String, Object>) bal.get("debt");
            long debtAmount = debt != null ? ((Number) debt.get("amount")).longValue() : 0;
            assertThat(debtAmount).isEqualTo(0);
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
}
