package io.runcycles.protocol.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
@DisplayName("Metrics correctness (Micrometer)")
class MetricsCorrectnessIntegrationTest extends BaseIntegrationTest {

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
}
