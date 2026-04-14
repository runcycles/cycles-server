package io.runcycles.protocol.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Autowired
    private MeterRegistry meterRegistry;

    /** Sum of counts across every counter whose tags include the given filters. */
    private double counterCount(String name, String... kvs) {
        var search = meterRegistry.find(name);
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            search = search.tag(kvs[i], kvs[i + 1]);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

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

    @Nested
    @DisplayName("(C) thundering-herd retry")
    class ThunderingHerd {

        /**
         * The ops-realistic pattern when an idempotency cache has expired: a client
         * that originally timed out fires N concurrent retries with the same key.
         * All N arrive at the server within microseconds of each other, all N see
         * the idempotency cache gone, all N race through reserve.lua.
         *
         * Correctness depends on Redis's single-threaded Lua execution serialising
         * the calls: the first one to grab the script slot creates the reservation
         * and writes the idempotency cache; the rest see the cache and return the
         * same id. Without this test, that guarantee is a documented assumption
         * only — a future refactor (idempotency logic moved out of Lua into Java,
         * for instance) could silently violate it.
         */
        @Test
        @DisplayName("N concurrent retries after cache expiry produce exactly one reservation")
        void concurrentRetriesAfterExpiryProduceOneReservation() throws Exception {
            String idempotencyKey = UUID.randomUUID().toString();
            int concurrency = 10;

            // Prime the cache with a first reserve, then nuke it to simulate post-TTL.
            Map<String, Object> body = reservationBody(TENANT_A, 1_000);
            body.put("idempotency_key", idempotencyKey);
            ResponseEntity<Map> first = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            String firstId = (String) first.getBody().get("reservation_id");

            try (Jedis jedis = jedisPool.getResource()) {
                String idemKey = "idem:" + TENANT_A + ":reserve:" + idempotencyKey;
                jedis.del(idemKey);
                jedis.del(idemKey + ":hash");
            }

            double okBefore = counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "ALLOW", "reason", "OK");
            double replayBefore = counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "ALLOW", "reason", "IDEMPOTENT_REPLAY");

            // Fire N retries through the server simultaneously via a CountDownLatch.
            ExecutorService exec = Executors.newFixedThreadPool(concurrency);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<String> returnedIds = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
            AtomicInteger errors = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(concurrency);

            for (int i = 0; i < concurrency; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);
                        statuses.add(resp.getStatusCode().value());
                        if (resp.getStatusCode().is2xxSuccessful()) {
                            String id = (String) resp.getBody().get("reservation_id");
                            if (id != null) returnedIds.add(id);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            // I1: exactly one distinct reservation id returned across all N retries.
            // The first call re-creates a NEW reservation (cache was cleared); subsequent
            // retries land on the newly-written idempotency cache and replay it.
            Set<String> distinct = new HashSet<>(returnedIds);
            assertThat(distinct)
                    .as("N=%d retries produced multiple reservations: %s", concurrency, distinct)
                    .hasSize(1);
            String winningId = distinct.iterator().next();
            assertThat(winningId)
                    .as("new reservation id should differ from pre-expiry id")
                    .isNotEqualTo(firstId);

            // I2: every request got a clean 200.
            assertThat(new HashSet<>(statuses)).containsExactly(200);
            assertThat(errors.get()).as("no HTTP errors expected").isZero();

            // I3: exactly one Redis reservation hash exists for this id (no duplicates).
            try (Jedis jedis = jedisPool.getResource()) {
                Map<String, String> hash = jedis.hgetAll("reservation:res_" + winningId);
                assertThat(hash).isNotEmpty();
                assertThat(hash.get("idempotency_key")).isEqualTo(idempotencyKey);
            }

            // I4: metric tags reflect reality. Exactly one reserve.lua call produced a
            // real reservation (reason=OK); the remaining N-1 took the idempotent-replay
            // branch (reason=IDEMPOTENT_REPLAY). A wrong-tag regression (e.g. marking
            // all N as OK, or all N as REPLAY) would surface here.
            double okDelta = counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "ALLOW", "reason", "OK") - okBefore;
            double replayDelta = counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "ALLOW", "reason", "IDEMPOTENT_REPLAY") - replayBefore;

            assertThat(okDelta + replayDelta)
                    .as("total reserve counter increments must equal concurrency (%d); ok=%.0f replay=%.0f",
                            concurrency, okDelta, replayDelta)
                    .isEqualTo((double) concurrency);
            assertThat(okDelta)
                    .as("exactly one retry re-created the reservation (OK); rest replayed")
                    .isEqualTo(1.0);
            assertThat(replayDelta)
                    .as("remaining %d retries must have tagged IDEMPOTENT_REPLAY", concurrency - 1)
                    .isEqualTo((double) (concurrency - 1));
        }
    }
}
