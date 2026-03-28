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

@DisplayName("Tenant Defaults & Budget Status Integration Tests")
class TenantDefaultsIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("Tenant Defaults")
    class TenantDefaults {

        @Test
        void shouldUseTenantsDefaultOveragePolicyWhenRequestOmitsIt() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // Set tenant default to ALLOW_IF_AVAILABLE
                seedTenant(jedis, TENANT_A, "ALLOW_IF_AVAILABLE", null, null, null);
            }

            // Create reservation WITHOUT specifying overage_policy
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 1000));
            // No overage_policy → should use tenant default ALLOW_IF_AVAILABLE

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit with overage (2000 vs 1000 reserved) — should succeed with ALLOW_IF_AVAILABLE
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void shouldRejectOverageWhenTenantDefaultIsReject() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // Tenant default is REJECT
                seedTenant(jedis, TENANT_A, "REJECT", null, null, null);
            }

            // Create reservation WITHOUT specifying overage_policy
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 1000));

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit with overage — should fail with BUDGET_EXCEEDED
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(409);
            assertThat(commitResp.getBody().get("error")).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldPreferExplicitPolicyOverTenantDefault() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // Tenant default is ALLOW_IF_AVAILABLE
                seedTenant(jedis, TENANT_A, "ALLOW_IF_AVAILABLE", null, null, null);
            }

            // Create reservation WITH explicit REJECT policy
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("overage_policy", "REJECT");  // override tenant default

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit with overage — should fail because explicit REJECT overrides tenant default
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(409);
            assertThat(commitResp.getBody().get("error")).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldUseTenantsDefaultOveragePolicyForEvents() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                seedTenant(jedis, TENANT_A, "ALLOW_IF_AVAILABLE", null, null, null);
            }

            // Event WITHOUT specifying overage_policy → uses tenant default
            Map<String, Object> body = eventBody(TENANT_A, 500);

            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A, body);
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            assertThat(resp.getBody().get("status")).isEqualTo("APPLIED");
        }

        @Test
        void shouldCapTtlToTenantMaxReservationTtl() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // Tenant max TTL is 10 seconds
                seedTenant(jedis, TENANT_A, null, null, 10000L, null);
            }

            // Request 60s TTL, but tenant max is 10s
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 1000));
            body.put("ttl_ms", 60000);  // 60s
            body.put("overage_policy", "REJECT");

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);

            // Verify that the reservation expires sooner due to TTL cap
            long expiresAtMs = ((Number) resp.getBody().get("expires_at_ms")).longValue();
            long now = System.currentTimeMillis();
            // Should expire within ~10s, not ~60s
            assertThat(expiresAtMs - now).isLessThan(15000L);
        }

        @Test
        void shouldEnforceMaxReservationExtensions() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // Tenant allows only 2 extensions
                seedTenant(jedis, TENANT_A, null, null, null, 2);
            }

            // Create reservation
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 1000));
            body.put("ttl_ms", 60000);
            body.put("overage_policy", "REJECT");

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Extension 1: should succeed
            ResponseEntity<Map> ext1 = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(10000));
            assertThat(ext1.getStatusCode().value()).isEqualTo(200);

            // Extension 2: should succeed
            ResponseEntity<Map> ext2 = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(10000));
            assertThat(ext2.getStatusCode().value()).isEqualTo(200);

            // Extension 3: should fail (max 2 extensions)
            ResponseEntity<Map> ext3 = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(10000));
            assertThat(ext3.getStatusCode().value()).isEqualTo(409);
            assertThat(ext3.getBody().get("error")).isEqualTo("MAX_EXTENSIONS_EXCEEDED");
        }

        @Test
        void shouldUseTenantsDefaultTtlWhenRequestOmitsTtl() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // Tenant default TTL is 5 seconds
                seedTenant(jedis, TENANT_A, null, 5000L, null, null);
            }

            // Create reservation WITHOUT specifying ttl_ms
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 1000));
            body.put("overage_policy", "REJECT");
            // No ttl_ms → should use tenant default of 5000ms

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);

            long expiresAtMs = ((Number) resp.getBody().get("expires_at_ms")).longValue();
            long now = System.currentTimeMillis();
            // Should expire in ~5s (tenant default), not ~60s (hardcoded default)
            assertThat(expiresAtMs - now).isLessThan(10000L);
        }

        @Test
        void shouldWorkWithNoTenantRecordUsingHardcodedDefaults() throws Exception {
            // No tenant record seeded — test that hardcoded defaults still work

            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 1000));
            // No overage_policy, no ttl_ms

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Default overage_policy is ALLOW_IF_AVAILABLE — commit with overage should succeed (capped)
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));
            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void shouldUseAllowWithOverdraftTenantDefault() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                seedTenant(jedis, TENANT_A, "ALLOW_WITH_OVERDRAFT", null, null, null);
            }

            // Create reservation WITHOUT specifying overage_policy
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("subject", Map.of("tenant", TENANT_A));
            body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
            body.put("estimate", Map.of("unit", "TOKENS", "amount", 1000));

            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String reservationId = (String) reserveResp.getBody().get("reservation_id");

            // Commit with overage — should succeed via ALLOW_WITH_OVERDRAFT tenant default
            ResponseEntity<Map> commitResp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(2000));

            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
            assertThat(commitResp.getBody().get("status")).isEqualTo("COMMITTED");
        }
    }

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
