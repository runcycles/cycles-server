package io.runcycles.protocol.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers what happens to idempotent retries after the cache that backs them has expired.
 *
 * Two distinct caches are tested:
 *
 *   (A) Reserve idempotency: key {@code idem:{tenant}:reserve:{K}} stored by reserve.lua
 *       with TTL = max(ttl_ms + grace_ms, 24h). Once it expires, a late retry with the
 *       same K is effectively a fresh request and should create a NEW reservation.
 *
 *   (B) Commit idempotency: the key is stored directly on the reservation hash as
 *       {@code committed_idempotency_key}. If the field is gone (e.g. because the audit
 *       record was manually scrubbed), a retry with the same K on a COMMITTED
 *       reservation must NOT silently "succeed" a second time — spec says it returns
 *       {@code 409 RESERVATION_FINALIZED} so the caller knows the state is terminal.
 *
 * Deterministic approach: rather than waiting out a real TTL, we delete the cache key
 * directly via Jedis. This exercises the same Lua branches the service takes after the
 * PSETEX expiry in production and keeps the test fast and reproducible.
 */
@DisplayName("Idempotency cache expiry")
class IdempotencyCacheExpiryIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("(A) reserve-cache expiry")
    class ReserveCacheExpiry {

        @Test
        void lateRetryAfterTtlCreatesNewReservation() {
            String idempotencyKey = UUID.randomUUID().toString();

            // First reserve with key K
            Map<String, Object> body = reservationBody(TENANT_A, 1_000);
            body.put("idempotency_key", idempotencyKey);
            ResponseEntity<Map> first = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            String firstId = (String) first.getBody().get("reservation_id");

            // Simulate TTL expiry of the reserve idempotency cache
            try (Jedis jedis = jedisPool.getResource()) {
                String idemKey = "idem:" + TENANT_A + ":reserve:" + idempotencyKey;
                long deleted = jedis.del(idemKey);
                jedis.del(idemKey + ":hash");
                assertThat(deleted)
                        .as("reserve idempotency key should have existed and been deleted")
                        .isEqualTo(1);
            }

            // Retry with same K + same body. Cache is gone → treated as a fresh request.
            ResponseEntity<Map> second = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(second.getStatusCode().value()).isEqualTo(200);
            String secondId = (String) second.getBody().get("reservation_id");

            assertThat(secondId)
                    .as("after reserve-cache expiry, a retry must produce a NEW reservation id")
                    .isNotEqualTo(firstId);

            // Both reservations should exist independently in Redis.
            Map<String, String> firstHash = getReservationStateFromRedis(firstId);
            Map<String, String> secondHash = getReservationStateFromRedis(secondId);
            assertThat(firstHash.get("state")).isEqualTo("ACTIVE");
            assertThat(secondHash.get("state")).isEqualTo("ACTIVE");
        }

        @Test
        void replayBeforeTtlReturnsSameReservationId() {
            // Establishes the "happy" side of the contrast: with the cache still present,
            // a retry must return the same id (no duplicate reservation created).
            String idempotencyKey = UUID.randomUUID().toString();
            Map<String, Object> body = reservationBody(TENANT_A, 1_000);
            body.put("idempotency_key", idempotencyKey);

            ResponseEntity<Map> first = post("/v1/reservations", API_KEY_SECRET_A, body);
            ResponseEntity<Map> replay = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody().get("reservation_id"))
                    .isEqualTo(first.getBody().get("reservation_id"));
        }
    }

    @Nested
    @DisplayName("(B) commit-record scrub")
    class CommitCacheExpiry {

        @Test
        void retryAfterCommittedIdempotencyKeyScrubbedReturnsFinalized() {
            // Reserve + commit normally
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
            String commitKey = UUID.randomUUID().toString();
            Map<String, Object> commit = new HashMap<>();
            commit.put("idempotency_key", commitKey);
            commit.put("actual", Map.of("unit", "TOKENS", "amount", 500));
            ResponseEntity<Map> first = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commit);
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            assertThat(first.getBody().get("status")).isEqualTo("COMMITTED");

            // Scrub the committed_idempotency_key field on the reservation hash.
            try (Jedis jedis = jedisPool.getResource()) {
                String key = "reservation:res_" + reservationId;
                long removed = jedis.hdel(key, "committed_idempotency_key");
                assertThat(removed)
                        .as("committed_idempotency_key should have been present after commit")
                        .isEqualTo(1);
            }

            // Retry with same key. State is COMMITTED but stored key no longer matches → 409.
            ResponseEntity<Map> retry = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commit);
            assertThat(retry.getStatusCode().value())
                    .as("retry after scrub must not silently succeed a second time")
                    .isEqualTo(409);
            assertThat(retry.getBody().get("error")).isEqualTo("RESERVATION_FINALIZED");
        }
    }
}
