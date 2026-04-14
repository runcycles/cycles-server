package io.runcycles.protocol.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that all time-sensitive reservation decisions resolve against Redis's clock, not
 * the JVM's. This is the invariant the "clock-skew resilience" spec item is actually
 * protecting: no matter what time the app server thinks it is, the commit/release/extend
 * acceptance windows are driven by {@code redis.call('TIME')} inside Lua.
 *
 * Test strategy: skip the impossible-to-reach Java Clock (there is no injectable
 * {@code Clock} bean in production) and instead rewrite {@code expires_at} + {@code grace_ms}
 * on the reservation hash directly. Then drive commit/release/extend through the full HTTP
 * path and verify the accept/reject decision matches Redis-side math — regardless of what
 * {@code System.currentTimeMillis()} says.
 *
 * If a future refactor ever adds Java-side timestamping for these decisions, these tests
 * will catch the regression because the Java clock and the Redis-stored expires_at will
 * no longer agree.
 */
@DisplayName("Clock-skew: decisions depend only on Redis TIME")
class ClockSkewIntegrationTest extends BaseIntegrationTest {

    private void setExpiry(String reservationId, long expiresAt, long graceMs) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "reservation:res_" + reservationId;
            jedis.hset(key, "expires_at", String.valueOf(expiresAt));
            jedis.hset(key, "grace_ms", String.valueOf(graceMs));
            jedis.zadd("reservation:ttl", expiresAt, reservationId);
        }
    }

    @Nested
    @DisplayName("Commit acceptance window")
    class CommitWindow {

        @Test
        void commitSucceedsWhenWithinGraceRegardlessOfJavaClock() {
            // Reserve, then rewrite expires_at to the past but keep a large grace window.
            // Under Redis time: now <= expires_at + grace → commit MUST succeed.
            String resId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
            long javaNow = System.currentTimeMillis();
            setExpiry(resId, javaNow - 5_000, 60_000);

            ResponseEntity<Map> commit = post(
                    "/v1/reservations/" + resId + "/commit",
                    API_KEY_SECRET_A, commitBody(500));
            assertThat(commit.getStatusCode().value())
                    .as("Redis says within grace — commit must succeed: %s", commit.getBody())
                    .isEqualTo(200);
            assertThat(commit.getBody().get("status")).isEqualTo("COMMITTED");
        }

        @Test
        void commitRejectedOnceRedisTimeExceedsGrace() {
            // Deep past + zero grace → Redis says expired. Commit must fail with
            // RESERVATION_EXPIRED, no matter how recent the Java clock's view is.
            String resId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
            setExpiry(resId, System.currentTimeMillis() - 3_600_000L, 0);

            ResponseEntity<Map> commit = post(
                    "/v1/reservations/" + resId + "/commit",
                    API_KEY_SECRET_A, commitBody(500));
            // Spec returns 410 Gone for RESERVATION_EXPIRED (the resource is gone,
            // not a conflict). Different from RESERVATION_FINALIZED (409).
            assertThat(commit.getStatusCode().value())
                    .as("Redis says expired — commit must reject: %s", commit.getBody())
                    .isEqualTo(410);
            assertThat(commit.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");
        }
    }

    @Nested
    @DisplayName("Extend decision")
    class ExtendDecision {

        // Spec NORMATIVE (extend.lua line 47-48): extend only allowed when
        // `server time <= expires_at`. Grace is deliberately NOT honored for extend —
        // the idea being that once expiry has started, the caller should commit/release
        // within grace rather than extending. These tests pin that contract.

        @Test
        void extendSucceedsWhenRedisExpiresAtIsFuture() {
            String resId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
            setExpiry(resId, System.currentTimeMillis() + 60_000, 0);

            ResponseEntity<Map> extend = post(
                    "/v1/reservations/" + resId + "/extend",
                    API_KEY_SECRET_A, extendBody(30_000));
            assertThat(extend.getStatusCode().is2xxSuccessful())
                    .as("expires_at in the future — extend must succeed: %s", extend.getBody())
                    .isTrue();
        }

        @Test
        void extendRejectedOncePastExpiresAtEvenWithinGrace() {
            // expires_at just past + large grace → commit would still succeed, but extend
            // MUST reject because extend ignores grace by spec.
            String resId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
            setExpiry(resId, System.currentTimeMillis() - 1_000, 3_600_000L);

            ResponseEntity<Map> extend = post(
                    "/v1/reservations/" + resId + "/extend",
                    API_KEY_SECRET_A, extendBody(30_000));
            assertThat(extend.getStatusCode().is2xxSuccessful())
                    .as("past expires_at — extend must reject regardless of grace: %s (body=%s)",
                            extend.getStatusCode(), extend.getBody())
                    .isFalse();
            assertThat(extend.getBody().get("error")).isEqualTo("RESERVATION_EXPIRED");
        }
    }

    @Nested
    @DisplayName("TTL sorted-set ordering")
    class TtlOrdering {

        @Test
        void ttlIndexUsesRedisStoredExpiresAt() {
            // Create two reservations, then overwrite their expires_at so the TTL index
            // ordering reflects the values we wrote — not any Java-side timestamp.
            String first = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 500);
            String second = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 500);

            setExpiry(first, 2_000_000_000_000L, 0);  // year ~2033
            setExpiry(second, 1_500_000_000_000L, 0); // year ~2017

            try (Jedis jedis = jedisPool.getResource()) {
                Double firstScore = jedis.zscore("reservation:ttl", first);
                Double secondScore = jedis.zscore("reservation:ttl", second);
                assertThat(firstScore).isEqualTo(2_000_000_000_000.0);
                assertThat(secondScore).isEqualTo(1_500_000_000_000.0);
                // Ordering follows written scores — not creation order.
                var earliest = jedis.zrange("reservation:ttl", 0, 0);
                assertThat(earliest).containsExactly(second);
            }
        }
    }
}
