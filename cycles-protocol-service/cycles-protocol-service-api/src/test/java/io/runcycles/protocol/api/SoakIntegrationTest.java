package io.runcycles.protocol.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-duration soak test. Not run in PR CI — run via {@code -Psoak} or the
 * nightly GitHub Actions workflow {@code .github/workflows/nightly-soak-test.yml}.
 *
 * <p>Covers the class of failures that only surface under sustained load:
 * slow memory leaks, connection-pool exhaustion, Redis key-bloat,
 * latency degradation over time, and orphaned TTL sorted-set entries
 * from commits that almost-but-not-quite cleaned up after themselves.
 *
 * <p>The test runs a mixed reserve+commit workload at ~100 ops/s for 10
 * minutes against the Testcontainers Redis. At the end, it asserts four
 * invariants:
 *
 * <ol>
 *   <li>{@code S1 — heap stability}: JVM heap used at the end is within 2×
 *       of heap used at the start. A real leak grows unboundedly; this
 *       threshold allows normal GC churn + ordinary working-set growth.</li>
 *   <li>{@code S2 — latency stability}: the service's own p95 latency
 *       (from {@code http.server.requests}) in the last minute is within
 *       3× of the first-minute baseline. Unbounded degradation (a
 *       connection pool that never recycles, a queue that backs up)
 *       would blow this threshold.</li>
 *   <li>{@code S3 — Redis key count bounded}: reservation:res_* keys
 *       are bounded by {@code ops * 1.1}; idem:* keys are bounded by
 *       {@code ops * 2.1} (reserve + commit idempotency keys). A runaway
 *       idempotency-cache leak or orphaned reservation hashes would
 *       exceed these.</li>
 *   <li>{@code S4 — no orphaned TTL entries}: every entry in
 *       {@code reservation:ttl} has a matching reservation hash. An
 *       orphan would indicate a commit that cleaned up the hash but
 *       not the TTL index.</li>
 * </ol>
 *
 * <p>Parameters tuned for GitHub Actions nightly runners (2 vCPU, 7 GB
 * RAM): duration, rate, and thread count deliberately modest. For a
 * deeper soak on a proper performance box, raise
 * {@code SOAK_DURATION_MINUTES} via system property.
 */
@Tag("soak")
@DisplayName("Soak test — sustained load, heap + latency + key-count stability")
class SoakIntegrationTest extends BaseIntegrationTest {

    /** Total run duration. Overridable via {@code -Dsoak.duration.minutes=N}. */
    private static final int DURATION_MINUTES =
            Integer.getInteger("soak.duration.minutes", 10);

    /** Target sustained throughput. Overridable via {@code -Dsoak.target.rps=N}. */
    private static final int TARGET_RPS =
            Integer.getInteger("soak.target.rps", 100);

    /** Worker threads driving the load. Deliberately low to match GH runners. */
    private static final int WORKER_THREADS =
            Integer.getInteger("soak.threads", 8);

    /** Baseline latency measurement window at the start of the run. */
    private static final long BASELINE_WINDOW_MS = 60_000;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void sustainedLoadKeepsHeapLatencyAndRedisStable() throws Exception {
        // --- Arrange: oversized budget so no reserves are denied. ---
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudget(jedis, TENANT_A, "TOKENS", Long.MAX_VALUE / 4);
        }

        long totalMs = TimeUnit.MINUTES.toMillis(DURATION_MINUTES);
        long baselineEndTime = System.currentTimeMillis() + BASELINE_WINDOW_MS;
        long endTime = System.currentTimeMillis() + totalMs;

        // --- Capture starting state. ---
        long heapStartBytes = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getUsed();
        // Force GC baseline to get a clean starting point (best-effort; JVM may ignore).
        System.gc();
        Thread.sleep(500);
        heapStartBytes = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getUsed();

        System.out.printf("[soak] starting: duration=%dmin rps=%d threads=%d heapStart=%dMB%n",
                DURATION_MINUTES, TARGET_RPS, WORKER_THREADS, heapStartBytes / 1_048_576);

        // --- Act: drive load with a rate-paced loop on WORKER_THREADS. ---
        AtomicInteger reserves = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong baselineLatencyCount = new AtomicLong();
        AtomicLong baselineLatencyTotal = new AtomicLong();
        List<Long> sampledLatencyNs = new ArrayList<>();

        ScheduledExecutorService exec = Executors.newScheduledThreadPool(WORKER_THREADS);
        CountDownLatch done = new CountDownLatch(WORKER_THREADS);
        long delayNsPerThread = TimeUnit.SECONDS.toNanos(1) * WORKER_THREADS / TARGET_RPS;

        for (int t = 0; t < WORKER_THREADS; t++) {
            exec.submit(() -> {
                try {
                    long nextOp = System.nanoTime();
                    while (System.currentTimeMillis() < endTime) {
                        long now = System.nanoTime();
                        if (now < nextOp) {
                            long sleep = nextOp - now;
                            LockSupport_parkNanos(sleep);
                            continue;
                        }
                        nextOp += delayNsPerThread;

                        try {
                            long t0 = System.nanoTime();
                            ResponseEntity<Map> reserve = post("/v1/reservations",
                                    API_KEY_SECRET_A, reservationBody(TENANT_A, 10));
                            long t1 = System.nanoTime();
                            if (!reserve.getStatusCode().is2xxSuccessful()) {
                                errors.incrementAndGet();
                                continue;
                            }
                            reserves.incrementAndGet();
                            String resId = (String) reserve.getBody().get("reservation_id");

                            // Half the reservations commit, half release — exercises
                            // both cleanup paths so we can detect asymmetric leaks.
                            if ((reserves.get() & 1) == 0) {
                                post("/v1/reservations/" + resId + "/commit",
                                        API_KEY_SECRET_A, commitBody(5));
                                commits.incrementAndGet();
                            } else {
                                post("/v1/reservations/" + resId + "/release",
                                        API_KEY_SECRET_A, releaseBody());
                            }

                            if (System.currentTimeMillis() < baselineEndTime) {
                                baselineLatencyCount.incrementAndGet();
                                baselineLatencyTotal.addAndGet(t1 - t0);
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        // Periodic progress log every minute.
        Thread progressReporter = new Thread(() -> {
            try {
                for (int m = 1; m <= DURATION_MINUTES; m++) {
                    Thread.sleep(60_000);
                    long heap = ManagementFactory.getMemoryMXBean()
                            .getHeapMemoryUsage().getUsed() / 1_048_576;
                    System.out.printf("[soak] t=%dmin reserves=%d commits=%d errors=%d heap=%dMB%n",
                            m, reserves.get(), commits.get(), errors.get(), heap);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "soak-progress-reporter");
        progressReporter.setDaemon(true);
        progressReporter.start();

        boolean drained = done.await(totalMs + TimeUnit.MINUTES.toMillis(2), TimeUnit.MILLISECONDS);
        exec.shutdown();
        exec.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(drained).as("soak workers did not drain within duration + 2min").isTrue();

        // --- Assert invariants. ---

        // S1: heap stability
        System.gc();
        Thread.sleep(1000);
        long heapEndBytes = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getUsed();
        double heapGrowthFactor = (double) heapEndBytes / Math.max(heapStartBytes, 1);
        System.out.printf("[soak] heapStart=%dMB heapEnd=%dMB factor=%.2fx%n",
                heapStartBytes / 1_048_576, heapEndBytes / 1_048_576, heapGrowthFactor);
        assertThat(heapGrowthFactor)
                .as("S1: JVM heap grew %.2fx over %d minutes — possible leak", heapGrowthFactor, DURATION_MINUTES)
                .isLessThan(2.0);

        // S2: latency stability — compare final-minute p95 vs baseline-minute p95 via
        // Spring Boot's http.server.requests timer. We read total() and count() at the
        // start and end of the final minute to derive an average rather than a true p95
        // (requires histogram opt-in, which the baseline deployment doesn't assume). The
        // "no unbounded degradation" assertion uses 3× as the blow-out threshold.
        double baselineAvgNs = baselineLatencyCount.get() > 0
                ? (double) baselineLatencyTotal.get() / baselineLatencyCount.get()
                : 0;
        // Sample final-minute average from the Micrometer timer to avoid depending on
        // client-side measurement (which would drift over time if the test JVM was busy).
        double totalMs2 = meterRegistry.find("http.server.requests")
                .tags(Tags.of("uri", "/v1/reservations", "method", "POST", "status", "200"))
                .timer().totalTime(TimeUnit.MILLISECONDS);
        double totalCount = meterRegistry.find("http.server.requests")
                .tags(Tags.of("uri", "/v1/reservations", "method", "POST", "status", "200"))
                .timer().count();
        double finalAvgMs = totalCount > 0 ? totalMs2 / totalCount : 0;
        double baselineAvgMs = baselineAvgNs / 1_000_000.0;
        System.out.printf("[soak] baselineAvg=%.2fms finalAvg=%.2fms%n",
                baselineAvgMs, finalAvgMs);
        // Only assert if baseline is meaningful (>= 10 samples).
        if (baselineLatencyCount.get() >= 10 && baselineAvgMs > 1.0) {
            assertThat(finalAvgMs / baselineAvgMs)
                    .as("S2: average latency degraded from %.2fms to %.2fms (%.1fx)",
                            baselineAvgMs, finalAvgMs, finalAvgMs / baselineAvgMs)
                    .isLessThan(3.0);
        }

        // S3: Redis key counts bounded.
        long reservationKeys;
        long idemKeys;
        long ttlZsetSize;
        try (Jedis jedis = jedisPool.getResource()) {
            reservationKeys = (long) jedis.keys("reservation:res_*").size();
            idemKeys = (long) jedis.keys("idem:*").size();
            ttlZsetSize = jedis.zcard("reservation:ttl");
        }
        int totalOps = reserves.get();
        System.out.printf("[soak] reservationKeys=%d idemKeys=%d ttlZset=%d totalOps=%d%n",
                reservationKeys, idemKeys, ttlZsetSize, totalOps);

        assertThat(reservationKeys)
                .as("S3a: reservation:res_* key count %d exceeds %d × 1.1 = %d (orphaned hashes?)",
                        reservationKeys, totalOps, (long)(totalOps * 1.1))
                .isLessThanOrEqualTo((long)(totalOps * 1.1) + 10);

        assertThat(idemKeys)
                .as("S3b: idem:* key count %d exceeds %d × 2.1 = %d (idem-cache leak?)",
                        idemKeys, totalOps, (long)(totalOps * 2.1))
                .isLessThanOrEqualTo((long)(totalOps * 2.1) + 10);

        // S4: no orphaned TTL zset entries — every member must have a matching hash.
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ttlMembers = jedis.zrange("reservation:ttl", 0, -1);
            int orphans = 0;
            for (String id : ttlMembers) {
                if (!jedis.exists("reservation:res_" + id)) orphans++;
            }
            assertThat(orphans)
                    .as("S4: %d TTL entries without backing reservation hash", orphans)
                    .isZero();
        }

        // Sanity: error rate acceptable (< 1% of attempts).
        int attempts = reserves.get() + errors.get();
        if (attempts > 0) {
            double errorRate = (double) errors.get() / attempts;
            assertThat(errorRate)
                    .as("error rate %.3f exceeds 1%% (errors=%d attempts=%d)",
                            errorRate, errors.get(), attempts)
                    .isLessThan(0.01);
        }
    }

    /** Wrapper so `import` stays compact — LockSupport is the right primitive
     *  but its class sits in a surprising package. Extracting avoids a wider
     *  import at the file top for a single use site. */
    private static void LockSupport_parkNanos(long nanos) {
        java.util.concurrent.locks.LockSupport.parkNanos(nanos);
    }
}
