package io.runcycles.protocol.api;

import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent load benchmarks for Cycles Protocol operations.
 *
 * Measures throughput (ops/sec) and latency under concurrent load by running
 * multiple threads executing Reserve→Commit lifecycles simultaneously.
 *
 * Tests ramp from 8 → 16 → 32 concurrent threads to reveal contention
 * at the Redis connection pool (max 50), Lua script execution, and
 * Spring Boot request processing layers.
 *
 * Run separately: mvn test -Dgroups=benchmark
 */
@DisplayName("Concurrent Load Benchmarks")
@Tag("benchmark")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CyclesProtocolConcurrentBenchmarkTest extends BaseIntegrationTest {

    private static final int WARMUP_OPS = 50;
    private static final long MEASURE_DURATION_MS = 5_000;

    private static final List<ConcurrencyResult> ALL_RESULTS = new ArrayList<>();

    record ConcurrencyResult(int threads, long totalOps, double opsPerSec,
                             long p50, long p95, long p99, long min, long max, int errors) {}

    @AfterAll
    static void printSummary() {
        if (ALL_RESULTS.isEmpty()) return;

        System.out.println();
        System.out.println("+----------+----------+-----------+--------+--------+--------+--------+--------+--------+");
        System.out.println("| Threads  | Total Ops| Ops/sec   |  p50   |  p95   |  p99   |  min   |  max   | Errors |");
        System.out.println("+----------+----------+-----------+--------+--------+--------+--------+--------+--------+");
        for (ConcurrencyResult r : ALL_RESULTS) {
            System.out.printf("| %8d | %8d | %9.1f | %5.1fms| %5.1fms| %5.1fms| %5.1fms| %5.1fms| %6d |%n",
                    r.threads, r.totalOps, r.opsPerSec,
                    r.p50 / 1_000_000.0, r.p95 / 1_000_000.0, r.p99 / 1_000_000.0,
                    r.min / 1_000_000.0, r.max / 1_000_000.0, r.errors);
        }
        System.out.println("+----------+----------+-----------+--------+--------+--------+--------+--------+--------+");
        System.out.printf("  Duration per level: %ds (after %d warmup ops)%n",
                MEASURE_DURATION_MS / 1000, WARMUP_OPS);
        System.out.println();
    }

    @Test
    @Order(1)
    @DisplayName("Reserve→Commit lifecycle at 8 threads")
    void concurrentLifecycle_8threads() throws Exception {
        runConcurrentLifecycle(8);
    }

    @Test
    @Order(2)
    @DisplayName("Reserve→Commit lifecycle at 16 threads")
    void concurrentLifecycle_16threads() throws Exception {
        runConcurrentLifecycle(16);
    }

    @Test
    @Order(3)
    @DisplayName("Reserve→Commit lifecycle at 32 threads")
    void concurrentLifecycle_32threads() throws Exception {
        runConcurrentLifecycle(32);
    }

    private void runConcurrentLifecycle(int threadCount) throws Exception {
        // Re-seed budget with enough headroom for sustained concurrent load
        try (var jedis = jedisPool.getResource()) {
            seedBudget(jedis, TENANT_A, "TOKENS", 1_000_000_000L);
        }

        // Warm up: sequential operations to prime JIT, connection pool, EVALSHA cache
        for (int i = 0; i < WARMUP_OPS; i++) {
            String resId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            post("/v1/reservations/" + resId + "/commit", API_KEY_SECRET_A, commitBody(80));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ConcurrentLinkedQueue<Long> timings = new ConcurrentLinkedQueue<>();
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);

        // Submit worker tasks
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                while (running.get()) {
                    long start = System.nanoTime();
                    try {
                        Map<String, Object> reserveBody = reservationBody(TENANT_A, 100);
                        ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, reserveBody);
                        if (!reserveResp.getStatusCode().is2xxSuccessful()) {
                            errorCount.incrementAndGet();
                            continue;
                        }
                        String resId = (String) reserveResp.getBody().get("reservation_id");

                        ResponseEntity<Map> commitResp = post("/v1/reservations/" + resId + "/commit",
                                API_KEY_SECRET_A, commitBody(80));
                        if (!commitResp.getStatusCode().is2xxSuccessful()) {
                            errorCount.incrementAndGet();
                            continue;
                        }

                        long elapsed = System.nanoTime() - start;
                        timings.add(elapsed);
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            }));
        }

        // Release all threads and measure for MEASURE_DURATION_MS
        startLatch.countDown();
        Thread.sleep(MEASURE_DURATION_MS);
        running.set(false);

        // Wait for all threads to finish their current operation
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Collect and analyze results
        long[] sorted = timings.stream().mapToLong(Long::longValue).sorted().toArray();
        int totalOps = sorted.length;
        int errors = errorCount.get();
        double opsPerSec = totalOps / (MEASURE_DURATION_MS / 1000.0);

        ConcurrencyResult result;
        if (totalOps > 0) {
            result = new ConcurrencyResult(threadCount, totalOps, opsPerSec,
                    p(sorted, 50), p(sorted, 95), p(sorted, 99),
                    sorted[0], sorted[sorted.length - 1], errors);
        } else {
            result = new ConcurrencyResult(threadCount, 0, 0, 0, 0, 0, 0, 0, errors);
        }

        synchronized (ALL_RESULTS) {
            ALL_RESULTS.add(result);
        }

        System.out.printf("[Concurrent] %2d threads: %d ops in %ds = %.1f ops/s  p50=%.1fms  p95=%.1fms  p99=%.1fms  errors=%d%n",
                threadCount, totalOps, MEASURE_DURATION_MS / 1000, opsPerSec,
                totalOps > 0 ? sorted[percentileIndex(sorted.length, 50)] / 1_000_000.0 : 0,
                totalOps > 0 ? sorted[percentileIndex(sorted.length, 95)] / 1_000_000.0 : 0,
                totalOps > 0 ? sorted[percentileIndex(sorted.length, 99)] / 1_000_000.0 : 0,
                errors);

        // Assert no errors under load
        assertThat(errors).as("Errors at %d threads", threadCount).isZero();
        assertThat(totalOps).as("Total ops at %d threads", threadCount).isGreaterThan(0);
    }

    private static long p(long[] sorted, int percentile) {
        return sorted[percentileIndex(sorted.length, percentile)];
    }

    private static int percentileIndex(int length, int percentile) {
        return Math.min((int) Math.ceil(percentile / 100.0 * length) - 1, length - 1);
    }
}
