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

@DisplayName("Scope & Expiry Integration Tests")
class ScopeAndExpiryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReservationExpiryService expiryService;

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

            // Spec normative: expired reservations MUST return 410 RESERVATION_EXPIRED
            ResponseEntity<Map> resp = get("/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(410);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");

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

            // Use Redis TIME (not System.currentTimeMillis) for expires_at to avoid clock
            // skew with expire.lua, which checks grace period using redis.call('TIME').
            try (Jedis jedis = jedisPool.getResource()) {
                List<String> t = jedis.time();
                long redisNow = Long.parseLong(t.get(0)) * 1000 + Long.parseLong(t.get(1)) / 1000;
                long expiresAt = redisNow - 5_000;
                String key = "reservation:res_" + reservationId;
                jedis.hset(key, "expires_at", String.valueOf(expiresAt));
                jedis.hset(key, "grace_ms", "60000");
                jedis.zadd("reservation:ttl", expiresAt, reservationId);
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

            // Spec normative: expired reservations MUST return 410 RESERVATION_EXPIRED
            ResponseEntity<Map> resp = get("/v1/reservations/" + reservationId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(410);
            assertThat(resp.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");
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
}
