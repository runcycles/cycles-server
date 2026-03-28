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

@DisplayName("Balance Response Format Integration Tests")
class BalanceResponseFormatIntegrationTest extends BaseIntegrationTest {

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

    @Nested
    @DisplayName("Debt Outstanding")
    class DebtOutstanding {

        @Test
        void shouldRejectNewReservationWhenDebtOutstandingAndNoOverdraftLimit() {
            // Spec: "When debt > 0, new reservations MUST be rejected with 409
            // DEBT_OUTSTANDING (unless explicitly allowed by policy)."
            // overdraft_limit=0 means no policy allowing debt → MUST reject.
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "overdraft_limit", "0");
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "debt", "1000");
            }

            ResponseEntity<Map> newResp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));

            assertThat(newResp.getStatusCode().value()).isEqualTo(409);
            assertThat(newResp.getBody().get("error")).isEqualTo("DEBT_OUTSTANDING");
        }

        @Test
        void shouldAllowNewReservationWhenDebtWithinOverdraftLimit() {
            // Spec: "When debt > 0, new reservations MUST be rejected ... (unless
            // explicitly allowed by policy)." — overdraft_limit > 0 is the explicit
            // policy that tolerates debt up to the limit.
            // Budget: allocated=1_000_000, overdraft_limit=100_000, debt=5_000.
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset("budget:tenant:" + TENANT_A + ":TOKENS", "debt", "5000");
            }

            ResponseEntity<Map> newResp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));

            assertThat(newResp.getStatusCode().value()).isEqualTo(200);
            assertThat(newResp.getBody().get("decision")).isEqualTo("ALLOW");
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
}
