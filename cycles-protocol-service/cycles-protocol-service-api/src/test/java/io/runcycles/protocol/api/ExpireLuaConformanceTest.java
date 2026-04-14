package io.runcycles.protocol.api;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct conformance tests for {@code lua/expire.lua}.
 *
 * These invoke the Lua script against the live Testcontainers Redis, bypassing the Java
 * service layer, so they pin down the script's contract independently of how it is wired
 * in production. They complement {@code ReservationExpiryServiceTest} (which exercises
 * Java orchestration with Mockito).
 *
 * Invariants enforced here (from expire.lua source):
 *   C1. Only ACTIVE → EXPIRED. Any other incoming state returns SKIP with that state,
 *       never rewrites the hash.
 *   C2. Releases the held estimate_amount back to `remaining` at every budgeted scope,
 *       and decrements `reserved` by the same amount — atomically.
 *   C3. Removes the reservation id from the `reservation:ttl` sorted set.
 *   C4. Idempotent: re-invoking on the same reservation after it expired is a no-op
 *       that returns SKIP state=EXPIRED.
 *   C5. Honors grace_ms — within (expires_at, expires_at + grace_ms], returns SKIP
 *       in_grace_period.
 *   C6. NOT_FOUND for missing reservations, and still cleans up the TTL index.
 *   C7. Uses Redis TIME (no Java clock), so behavior depends only on Redis server time.
 */
@DisplayName("expire.lua conformance")
class ExpireLuaConformanceTest extends BaseIntegrationTest {

    /** Cached script source, loaded from classpath once per test class. */
    private static final String EXPIRE_LUA = loadLuaScript("lua/expire.lua");

    private static String loadLuaScript(String resource) {
        try (InputStream in = ExpireLuaConformanceTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing classpath resource: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("failed to load " + resource, e);
        }
    }

    /** Seed a minimal ACTIVE reservation and budget pair; return the reservation id. */
    private String seedActiveReservation(Jedis jedis, long estimateAmount, long expiresAtEpochMs,
                                          long graceMs, String scopePath, long initialReserved) {
        String resId = "expiretest_" + System.nanoTime();
        String key = "reservation:res_" + resId;
        jedis.hset(key, Map.of(
                "reservation_id", "res_" + resId,
                "state", "ACTIVE",
                "estimate_amount", String.valueOf(estimateAmount),
                "estimate_unit", "TOKENS",
                "expires_at", String.valueOf(expiresAtEpochMs),
                "grace_ms", String.valueOf(graceMs),
                "affected_scopes", "[\"" + scopePath + "\"]",
                "budgeted_scopes", "[\"" + scopePath + "\"]"
        ));
        jedis.zadd("reservation:ttl", expiresAtEpochMs, resId);

        // Budget — seedScopeBudget defaults reserved=0, so layer the caller-provided value.
        String budgetKey = "budget:" + scopePath + ":TOKENS";
        jedis.hset(budgetKey, Map.of(
                "scope", scopePath,
                "unit", "TOKENS",
                "allocated", "1000000",
                "remaining", String.valueOf(1_000_000 - initialReserved),
                "reserved", String.valueOf(initialReserved),
                "spent", "0",
                "debt", "0",
                "overdraft_limit", "0",
                "is_over_limit", "false"
        ));
        return resId;
    }

    private Map<String, Object> evalExpire(Jedis jedis, String reservationId) throws Exception {
        Object raw = jedis.eval(EXPIRE_LUA, Collections.emptyList(), Collections.singletonList(reservationId));
        assertThat(raw).as("expire.lua returned null — script error").isNotNull();
        return objectMapper.readValue(raw.toString(), new TypeReference<Map<String, Object>>() {});
    }

    @Nested
    @DisplayName("C1 / C2 / C3 — ACTIVE → EXPIRED happy path")
    class ActiveToExpired {

        @Test
        void expiresPastGraceAndReleasesReservedBudget() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                long pastExpires = System.currentTimeMillis() - 10_000;
                String resId = seedActiveReservation(jedis, 500, pastExpires, 0, "tenant:" + TENANT_A, 500);

                Map<String, Object> result = evalExpire(jedis, resId);
                assertThat(result).containsEntry("status", "EXPIRED");

                // C1: state transitioned to EXPIRED with expired_at stamp
                Map<String, String> res = jedis.hgetAll("reservation:res_" + resId);
                assertThat(res).containsEntry("state", "EXPIRED");
                assertThat(res.get("expired_at")).as("expired_at must be stamped").isNotBlank();
                assertThat(Long.parseLong(res.get("expired_at")))
                        .as("expired_at should reflect recent Redis time, not a past value")
                        .isGreaterThan(pastExpires);

                // C2: reserved went back to remaining — 500 estimate, started with reserved=500
                Map<String, String> budget = jedis.hgetAll("budget:tenant:" + TENANT_A + ":TOKENS");
                assertThat(budget).containsEntry("reserved", "0");
                assertThat(budget).containsEntry("remaining", "1000000"); // 999500 + 500 released

                // C3: removed from TTL index
                Double score = jedis.zscore("reservation:ttl", resId);
                assertThat(score).as("reservation must be removed from reservation:ttl").isNull();

                // Audit TTL: 30-day PEXPIRE set
                long ttlMs = jedis.pttl("reservation:res_" + resId);
                assertThat(ttlMs)
                        .as("30-day audit TTL should be set")
                        .isGreaterThan(0)
                        .isLessThanOrEqualTo(2_592_000_000L);
            }
        }
    }

    @Nested
    @DisplayName("C1 — illegal-state transitions are no-ops")
    class IllegalStateGuards {

        @Test
        void skipsCommittedReservation() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                long pastExpires = System.currentTimeMillis() - 10_000;
                String resId = seedActiveReservation(jedis, 500, pastExpires, 0, "tenant:" + TENANT_A, 0);
                // Force COMMITTED state and capture snapshot
                jedis.hset("reservation:res_" + resId, "state", "COMMITTED");
                jedis.hset("reservation:res_" + resId, "charged_amount", "500");
                Map<String, String> before = jedis.hgetAll("reservation:res_" + resId);

                Map<String, Object> result = evalExpire(jedis, resId);
                assertThat(result).containsEntry("status", "SKIP");
                assertThat(result).containsEntry("state", "COMMITTED");

                // Hash untouched (no state rewrite, no expired_at stamp)
                Map<String, String> after = jedis.hgetAll("reservation:res_" + resId);
                assertThat(after).isEqualTo(before);
                // TTL index cleaned up even on SKIP
                assertThat(jedis.zscore("reservation:ttl", resId)).isNull();
            }
        }

        @Test
        void skipsReleasedReservation() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                long pastExpires = System.currentTimeMillis() - 10_000;
                String resId = seedActiveReservation(jedis, 500, pastExpires, 0, "tenant:" + TENANT_A, 0);
                jedis.hset("reservation:res_" + resId, "state", "RELEASED");

                Map<String, Object> result = evalExpire(jedis, resId);
                assertThat(result).containsEntry("status", "SKIP");
                assertThat(result).containsEntry("state", "RELEASED");
                assertThat(jedis.hget("reservation:res_" + resId, "state")).isEqualTo("RELEASED");
            }
        }
    }

    @Nested
    @DisplayName("C4 — idempotent re-invocation")
    class Idempotency {

        @Test
        void secondCallOnExpiredIsNoOp() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                long pastExpires = System.currentTimeMillis() - 10_000;
                String resId = seedActiveReservation(jedis, 500, pastExpires, 0, "tenant:" + TENANT_A, 500);

                Map<String, Object> first = evalExpire(jedis, resId);
                assertThat(first).containsEntry("status", "EXPIRED");
                Map<String, String> budgetAfterFirst = jedis.hgetAll("budget:tenant:" + TENANT_A + ":TOKENS");
                String expiredAtAfterFirst = jedis.hget("reservation:res_" + resId, "expired_at");

                Map<String, Object> second = evalExpire(jedis, resId);
                assertThat(second).containsEntry("status", "SKIP");
                assertThat(second).containsEntry("state", "EXPIRED");

                // Budget untouched on second call (no double-release of reserved budget)
                assertThat(jedis.hgetAll("budget:tenant:" + TENANT_A + ":TOKENS")).isEqualTo(budgetAfterFirst);
                // expired_at not overwritten
                assertThat(jedis.hget("reservation:res_" + resId, "expired_at")).isEqualTo(expiredAtAfterFirst);
            }
        }
    }

    @Nested
    @DisplayName("C5 — grace period honored")
    class GracePeriod {

        @Test
        void doesNotExpireWithinGraceWindow() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // expires_at = 1s ago, grace_ms = 60s → still within grace
                long expiresAt = System.currentTimeMillis() - 1_000;
                String resId = seedActiveReservation(jedis, 500, expiresAt, 60_000, "tenant:" + TENANT_A, 500);

                Map<String, Object> result = evalExpire(jedis, resId);
                assertThat(result).containsEntry("status", "SKIP");
                assertThat(result).containsEntry("reason", "in_grace_period");

                // Still ACTIVE, budget untouched
                assertThat(jedis.hget("reservation:res_" + resId, "state")).isEqualTo("ACTIVE");
                Map<String, String> budget = jedis.hgetAll("budget:tenant:" + TENANT_A + ":TOKENS");
                assertThat(budget).containsEntry("reserved", "500");
            }
        }

        @Test
        void expiresOnceGraceExceeded() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                // expires_at = 10s ago, grace_ms = 1s → past grace
                long expiresAt = System.currentTimeMillis() - 10_000;
                String resId = seedActiveReservation(jedis, 500, expiresAt, 1_000, "tenant:" + TENANT_A, 500);

                Map<String, Object> result = evalExpire(jedis, resId);
                assertThat(result).containsEntry("status", "EXPIRED");
            }
        }
    }

    @Nested
    @DisplayName("C6 — NOT_FOUND handling")
    class MissingReservations {

        @Test
        void returnsNotFoundAndCleansTtlIndex() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                String ghost = "ghost_" + System.nanoTime();
                // Place the ghost in the TTL index to verify cleanup even without the hash
                jedis.zadd("reservation:ttl", System.currentTimeMillis() - 100, ghost);

                Map<String, Object> result = evalExpire(jedis, ghost);
                assertThat(result).containsEntry("status", "NOT_FOUND");
                assertThat(jedis.zscore("reservation:ttl", ghost))
                        .as("NOT_FOUND path must still evict from TTL index")
                        .isNull();
            }
        }
    }

    @Nested
    @DisplayName("C7 — uses Redis TIME, not client-supplied time")
    class RedisTimeSource {

        @Test
        void ignoresExtraArgvForTimeSource() throws Exception {
            // expire.lua reads ARGV[1] only. Sending an extra bogus "future time" as ARGV[2]
            // must not prevent expiry of a reservation whose expires_at is actually past,
            // because the script uses redis.call('TIME') exclusively.
            try (Jedis jedis = jedisPool.getResource()) {
                long pastExpires = System.currentTimeMillis() - 10_000;
                String resId = seedActiveReservation(jedis, 500, pastExpires, 0, "tenant:" + TENANT_A, 500);

                // Call with an extra ARGV that would "trick" a naive implementation reading ARGV[2] as now
                Object raw = jedis.eval(
                        EXPIRE_LUA,
                        Collections.emptyList(),
                        java.util.Arrays.asList(resId, String.valueOf(pastExpires - 1_000_000)));
                Map<String, Object> result = objectMapper.readValue(
                        raw.toString(), new TypeReference<Map<String, Object>>() {});
                assertThat(result).containsEntry("status", "EXPIRED");
            }
        }
    }
}
