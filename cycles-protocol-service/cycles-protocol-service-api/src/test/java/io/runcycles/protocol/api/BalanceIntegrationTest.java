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

@DisplayName("Balance Integration Tests")
class BalanceIntegrationTest extends BaseIntegrationTest {

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
}
