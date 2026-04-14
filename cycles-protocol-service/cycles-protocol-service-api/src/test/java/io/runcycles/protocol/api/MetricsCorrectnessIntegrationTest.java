package io.runcycles.protocol.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import redis.clients.jedis.Jedis;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that Micrometer's {@code http.server.requests} timer is accurate for
 * reservation endpoints under known workloads.
 *
 * <p>Rationale for the chosen approach: the HTTP {@code /actuator/prometheus} endpoint
 * isn't reliably registered in the {@code @SpringBootTest(RANDOM_PORT)} context (the
 * handler falls through to static-resource resolution, returning 500 via
 * {@code NoResourceFoundException}). Rather than fight the test harness to expose it,
 * we inject the {@link MeterRegistry} directly — same metrics, same accuracy properties,
 * zero HTTP-layer wiring risk. A live-endpoint check still runs against the real server
 * when it's brought up manually; this test covers the counter accuracy contract.
 *
 * <p>If a future change adds custom reservation counters (reserve_total, commit_total,
 * etc.), extend this test to assert on them too. Until then the HTTP-layer timer is the
 * canonical signal.
 */
@TestPropertySource(properties = {
        "admin.api-key=metrics-test-admin-key",
        "cycles.metrics.tenant-tag.enabled=true"
})
@DisplayName("Metrics correctness (Micrometer)")
class MetricsCorrectnessIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_KEY = "metrics-test-admin-key";

    @Autowired
    private MeterRegistry meterRegistry;

    /** Count of timer samples for a uri+method+status label combination. */
    private long httpRequestCount(String uri, String method, String status) {
        Timer t = meterRegistry.find("http.server.requests")
                .tag("uri", uri)
                .tag("method", method)
                .tag("status", status)
                .timer();
        return t == null ? 0 : t.count();
    }

    /** Sum of counts across every counter whose tags include the given filters.
     *  Required because {@code Search.counter()} returns an arbitrary meter when
     *  multiple match — we want the aggregate, not a random one. */
    private double counterCount(String name, String... kvs) {
        var search = meterRegistry.find(name);
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            search = search.tag(kvs[i], kvs[i + 1]);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    @DisplayName("MeterRegistry is wired into the application context")
    void meterRegistryIsPresent() {
        assertThat(meterRegistry)
                .as("Micrometer MeterRegistry must be autowirable in the test context")
                .isNotNull();
    }

    @Test
    @DisplayName("reserve 200s increment http.server.requests by exactly N")
    void reserveRequestCountMatchesWorkload() {
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudget(jedis, TENANT_A, "TOKENS", 100_000_000);
        }

        long before = httpRequestCount("/v1/reservations", "POST", "200");

        int n = 25;
        for (int i = 0; i < n; i++) {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        }

        long after = httpRequestCount("/v1/reservations", "POST", "200");
        assertThat(after - before)
                .as("exactly N 200-status reserves should be recorded")
                .isEqualTo(n);
    }

    @Test
    @DisplayName("denials register under status=409 label")
    void budgetExceededDenialsShowUnderTheirStatusCode() {
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudgetWithOverdraftLimit(jedis, TENANT_A, "TOKENS", 100, 0);
        }

        long denialsBefore = httpRequestCount("/v1/reservations", "POST", "409");

        int n = 10;
        for (int i = 0; i < n; i++) {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1_000_000));
            assertThat(resp.getStatusCode().value()).isEqualTo(409);
        }

        long denialsAfter = httpRequestCount("/v1/reservations", "POST", "409");
        assertThat(denialsAfter - denialsBefore)
                .as("every denial must be counted under status=409")
                .isEqualTo(n);
    }

    // ---- Custom cycles.* counters (added v0.1.25.10) ----

    @Nested
    @DisplayName("cycles.reservations.reserve counter")
    class ReserveCounter {

        @Test
        @DisplayName("increments with decision=ALLOW + reason=OK on happy path")
        void allowReserveBumpsAllowLabel() {
            try (Jedis jedis = jedisPool.getResource()) {
                seedBudget(jedis, TENANT_A, "TOKENS", 100_000_000);
            }
            double before = counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "ALLOW", "reason", "OK");
            int n = 10;
            int ok = 0;
            for (int i = 0; i < n; i++) {
                ResponseEntity<java.util.Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                        reservationBody(TENANT_A, 100));
                if (resp.getStatusCode().value() == 200) ok++;
            }
            // All N must have succeeded — otherwise the counter assertion won't mean what
            // it says. Surface the actual outcome so debugging a failure is one-step.
            assertThat(ok).as("all %d reserves must succeed; only %d did", n, ok).isEqualTo(n);
            assertThat(counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "ALLOW", "reason", "OK") - before)
                    .isEqualTo((double) n);
        }

        @Test
        @DisplayName("increments with decision=DENY + reason=BUDGET_EXCEEDED on overflow")
        void denyReserveBumpsBudgetExceededLabel() {
            try (Jedis jedis = jedisPool.getResource()) {
                seedBudgetWithOverdraftLimit(jedis, TENANT_A, "TOKENS", 100, 0);
            }
            double before = counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "DENY", "reason", "BUDGET_EXCEEDED");
            int n = 5;
            for (int i = 0; i < n; i++) {
                post("/v1/reservations", API_KEY_SECRET_A, reservationBody(TENANT_A, 1_000_000));
            }
            assertThat(counterCount("cycles.reservations.reserve",
                    "tenant", TENANT_A, "decision", "DENY", "reason", "BUDGET_EXCEEDED") - before)
                    .isEqualTo((double) n);
        }
    }

    @Nested
    @DisplayName("cycles.reservations.commit counter")
    class CommitCounter {

        @Test
        @DisplayName("increments with COMMITTED + debtIncurred when overdraft flows")
        void overdraftCommitBumpsOverdraftCounter() {
            try (Jedis jedis = jedisPool.getResource()) {
                // Leave a tight headroom so a modest over-estimate creates debt.
                seedBudgetWithOverdraftLimit(jedis, TENANT_A, "TOKENS", 10_000, 50_000);
            }
            // Reserve close to the remaining; commit above remaining with ALLOW_WITH_OVERDRAFT.
            java.util.Map<String, Object> body = reservationBody(TENANT_A, 9_000);
            body.put("overage_policy", "ALLOW_WITH_OVERDRAFT");
            String id = (String) post("/v1/reservations", API_KEY_SECRET_A, body)
                    .getBody().get("reservation_id");

            double overdraftBefore = counterCount("cycles.overdraft.incurred", "tenant", TENANT_A);
            double commitBefore = counterCount("cycles.reservations.commit",
                    "tenant", TENANT_A, "decision", "COMMITTED", "reason", "OK");

            post("/v1/reservations/" + id + "/commit", API_KEY_SECRET_A, commitBody(30_000));

            assertThat(counterCount("cycles.reservations.commit",
                    "tenant", TENANT_A, "decision", "COMMITTED", "reason", "OK") - commitBefore)
                    .as("committed commit must bump the COMMITTED counter")
                    .isEqualTo(1.0);
            assertThat(counterCount("cycles.overdraft.incurred", "tenant", TENANT_A) - overdraftBefore)
                    .as("overdraft commit must bump overdraft.incurred")
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("cycles.reservations.release counter")
    class ReleaseCounter {

        @Test
        @DisplayName("tenant release bumps actor_type=tenant")
        void tenantReleaseBumpsTenantActorLabel() {
            String id = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
            double before = counterCount("cycles.reservations.release",
                    "tenant", TENANT_A, "actor_type", "tenant", "decision", "RELEASED");
            post("/v1/reservations/" + id + "/release", API_KEY_SECRET_A, releaseBody());
            assertThat(counterCount("cycles.reservations.release",
                    "tenant", TENANT_A, "actor_type", "tenant", "decision", "RELEASED") - before)
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("admin release bumps actor_type=admin_on_behalf_of")
        void adminReleaseBumpsAdminActorLabel() {
            String id = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
            double before = counterCount("cycles.reservations.release",
                    "tenant", TENANT_A, "actor_type", "admin_on_behalf_of", "decision", "RELEASED");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Admin-API-Key", ADMIN_KEY);
            restTemplate.exchange(baseUrl() + "/v1/reservations/" + id + "/release",
                    HttpMethod.POST, new HttpEntity<>(releaseBody(), headers), java.util.Map.class);

            assertThat(counterCount("cycles.reservations.release",
                    "tenant", TENANT_A, "actor_type", "admin_on_behalf_of", "decision", "RELEASED") - before)
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("cycles.reservations.expired counter")
    class ExpiredCounter {

        @Autowired
        private io.runcycles.protocol.data.service.ReservationExpiryService expiryService;

        @Test
        @DisplayName("bumped once per actual expiry transition, not per sweep candidate")
        void expiredCounterBumpsPerTransition() {
            // Create 3 reservations; force expire; run sweep → expect counter +3.
            String[] ids = new String[] {
                    createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100),
                    createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100),
                    createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100)
            };
            long past = System.currentTimeMillis() - 60_000;
            for (String id : ids) expireReservationInRedis(id, past);

            double before = counterCount("cycles.reservations.expired", "tenant", TENANT_A);
            expiryService.expireReservations();
            assertThat(counterCount("cycles.reservations.expired", "tenant", TENANT_A) - before)
                    .isEqualTo((double) ids.length);
        }
    }

    @Nested
    @DisplayName("cycles.events counter")
    class EventsCounter {

        @Test
        @DisplayName("applied events bump decision=APPLIED + reason=OK")
        void appliedEventBumpsCounter() {
            try (Jedis jedis = jedisPool.getResource()) {
                seedBudget(jedis, TENANT_A, "TOKENS", 100_000_000);
            }
            double before = counterCount("cycles.events",
                    "tenant", TENANT_A, "decision", "APPLIED", "reason", "OK");
            int n = 4;
            for (int i = 0; i < n; i++) {
                post("/v1/events", API_KEY_SECRET_A, eventBody(TENANT_A, 500));
            }
            assertThat(counterCount("cycles.events",
                    "tenant", TENANT_A, "decision", "APPLIED", "reason", "OK") - before)
                    .isEqualTo((double) n);
        }
    }

    @Test
    @DisplayName("counter is accurate under concurrent load (no lost increments)")
    void concurrentRequestCountIsAccurate() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudget(jedis, TENANT_A, "TOKENS", 100_000_000);
        }

        long before = httpRequestCount("/v1/reservations", "POST", "200");

        int threads = 8;
        int perThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        var exec = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                                reservationBody(TENANT_A, 100));
                        if (resp.getStatusCode().value() == 200) ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();

        long after = httpRequestCount("/v1/reservations", "POST", "200");
        // "No lost increments": the metric count equals the client-observed success
        // count, even with N threads racing on the same counter.
        assertThat(after - before)
                .as("metric count should match observed successes (%d from threads)", ok.get())
                .isEqualTo(ok.get());
    }

    @Test
    @DisplayName("custom cycles.reservations.reserve counter is accurate under concurrent load")
    void concurrentCustomCounterIsAccurate() throws Exception {
        // Sibling of concurrentRequestCountIsAccurate, but assert on the domain
        // counter instead of Spring Boot's HTTP timer. Micrometer counters use
        // AtomicLong underneath so this should be safe — the test guards against
        // future refactors that might introduce locking or shared-builder races
        // (e.g. an aspect that builds tags from a shared mutable map).
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudget(jedis, TENANT_A, "TOKENS", 100_000_000);
        }

        double before = counterCount("cycles.reservations.reserve",
                "tenant", TENANT_A, "decision", "ALLOW", "reason", "OK");

        int threads = 8;
        int perThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        var exec = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                                reservationBody(TENANT_A, 100));
                        if (resp.getStatusCode().value() == 200) ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();

        double after = counterCount("cycles.reservations.reserve",
                "tenant", TENANT_A, "decision", "ALLOW", "reason", "OK");
        assertThat(after - before)
                .as("cycles.reservations.reserve counter should match observed successes (%d)",
                        ok.get())
                .isEqualTo((double) ok.get());
    }
}
