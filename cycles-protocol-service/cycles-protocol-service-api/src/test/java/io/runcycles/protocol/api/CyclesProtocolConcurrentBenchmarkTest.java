package io.runcycles.protocol.api;

import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent load benchmarks for Cycles Protocol operations.
 *
 * Measures throughput (ops/sec) and latency under concurrent load by running
 * multiple threads executing Reserve→Commit lifecycles simultaneously.
 *
 * Lifecycle tests ramp from 8 → 16 → 32 concurrent threads. Reserve-only
 * tests ramp from 1 → 10 → 50 → 200 clients in two shapes: one shared
 * budget (the contention case) and independent leaf budgets (the sharded
 * case).
 *
 * Results are CI-environment sensitive — latency and throughput depend on
 * container resources, Redis container networking, and JVM warm-up.
 *
 * Run separately: mvn test -Pbenchmark
 * Select fan-out levels for isolated fresh-process trials with
 * -Dbenchmark.fanout.clients=1,10,50,200.
 */
@DisplayName("Concurrent Load Benchmarks")
@Tag("benchmark")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles({"test", "benchmark"})
class CyclesProtocolConcurrentBenchmarkTest extends BaseIntegrationTest {

    private static final int WARMUP_OPS = 50;
    private static final int MAX_WARMUP_CONCURRENCY = 50;
    private static final long MEASURE_DURATION_MS = 5_000;
    private static final List<Integer> DEFAULT_FANOUT_CLIENT_LEVELS =
            List.of(1, 10, 50, 200);
    private static final List<Integer> FANOUT_CLIENT_LEVELS =
            configuredFanoutClientLevels();
    private static final long FANOUT_ALLOCATION = 1_000_000_000_000L;
    private static final long FANOUT_RESERVE_AMOUNT = 100L;
    /** Max acceptable error rate (%) before failing the test */
    private static final double MAX_ERROR_RATE_PERCENT = 1.0;

    private static final List<ConcurrencyResult> ALL_RESULTS = new ArrayList<>();
    private static final List<FanoutResult> FANOUT_RESULTS = new ArrayList<>();

    record ConcurrencyResult(int threads, long totalOps, double opsPerSec,
                             long p50, long p95, long p99, long min, long max, int errors) {}

    record FanoutResult(String shape, int clients, long totalOps, double opsPerSec,
                        long p50, long p95, long p99, long min, long max,
                        int errors, double errorRatePercent, long ledgerMismatches) {}

    @AfterAll
    static void printSummary() {
        if (!ALL_RESULTS.isEmpty()) {
            System.out.println();
            System.out.println("+----------+----------+-----------+--------+--------+--------+--------+--------+--------+");
            System.out.println("| Threads  | Total Ops| Ops/sec   |  p50   |  p95   |  p99   |  min   |  max   | Errors |");
            System.out.println("+----------+----------+-----------+--------+--------+--------+--------+--------+--------+");
            for (ConcurrencyResult r : ALL_RESULTS) {
                System.out.printf("| %8d | %8d | %9.1f | %5.1fms| %5.1fms| %5.1fms| %5.1fms| %5.1fms| %6d |%n",
                        r.threads, r.totalOps, r.opsPerSec,
                        r.p50 / 1_000_000.0, r.p95 / 1_000_000.0,
                        r.p99 / 1_000_000.0, r.min / 1_000_000.0,
                        r.max / 1_000_000.0, r.errors);
            }
            System.out.println("+----------+----------+-----------+--------+--------+--------+--------+--------+--------+");
            System.out.printf("  Duration per level: %ds (after %d warmup ops)%n",
                    MEASURE_DURATION_MS / 1000, WARMUP_OPS);
            System.out.println();
        }

        if (FANOUT_RESULTS.isEmpty()) return;
        System.out.println("+-----------+---------+----------+-----------+--------+--------+--------+--------+--------+--------+----------+");
        System.out.println("| Shape     | Clients | Reserves | Reserves/s|  p50   |  p95   |  p99   |  min   |  max   | Errors | Ledger   |");
        System.out.println("+-----------+---------+----------+-----------+--------+--------+--------+--------+--------+--------+----------+");
        for (FanoutResult r : FANOUT_RESULTS) {
            System.out.printf(
                    "| %-9s | %7d | %8d | %9.1f | %5.1fms| %5.1fms| %5.1fms| %5.1fms| %5.1fms| %6d | %8d |%n",
                    r.shape, r.clients, r.totalOps, r.opsPerSec,
                    r.p50 / 1_000_000.0, r.p95 / 1_000_000.0,
                    r.p99 / 1_000_000.0, r.min / 1_000_000.0,
                    r.max / 1_000_000.0, r.errors, r.ledgerMismatches);
        }
        System.out.println("+-----------+---------+----------+-----------+--------+--------+--------+--------+--------+--------+----------+");
        System.out.println("  Reserve latency only; every successful reserve is reconciled against Redis ledger state.");
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

    @Test
    @Order(4)
    @DisplayName("Reserve fan-out from 1 to 200 clients on one shared budget")
    void concurrentReserve_sharedBudget() throws Exception {
        for (int clients : FANOUT_CLIENT_LEVELS) {
            runConcurrentReserveFanout(false, clients);
        }
    }

    @Test
    @Order(5)
    @DisplayName("Reserve fan-out from 1 to 200 clients on independent leaf budgets")
    void concurrentReserve_independentBudgets() throws Exception {
        for (int clients : FANOUT_CLIENT_LEVELS) {
            runConcurrentReserveFanout(true, clients);
        }
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
        try {
            ConcurrentLinkedQueue<Long> timings = new ConcurrentLinkedQueue<>();
            AtomicInteger errorCount = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicBoolean running = new AtomicBoolean(true);

            // Submit worker tasks
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
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

                            timings.add(System.nanoTime() - start);
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                });
            }

            // Release all threads and measure for MEASURE_DURATION_MS
            startLatch.countDown();
            Thread.sleep(MEASURE_DURATION_MS);
            running.set(false);

            // Wait for in-flight operations to complete
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

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

            // Allow small error rate for CI environment transient failures
            int totalAttempts = totalOps + errors;
            double errorRate = totalAttempts > 0 ? (errors * 100.0 / totalAttempts) : 0;
            assertThat(errorRate)
                    .as("Error rate at %d threads (errors=%d, total=%d)", threadCount, errors, totalAttempts)
                    .isLessThan(MAX_ERROR_RATE_PERCENT);
            assertThat(totalOps).as("Total ops at %d threads", threadCount).isGreaterThan(0);
        } finally {
            executor.shutdownNow();
        }
    }

    private void runConcurrentReserveFanout(
            boolean isolated,
            int clientCount) throws Exception {
        prepareFanoutBudgets(isolated, clientCount);

        // Prime JIT, auth cache, HTTP connection management, and EVALSHA
        // before resetting the ledger. The bounded ramp exercises every
        // logical client without turning 200 simultaneous cold connections
        // into a warmup-only transport failure.
        warmUpFanout(isolated, clientCount);
        prepareFanoutBudgets(isolated, clientCount);

        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        try {
            ConcurrentLinkedQueue<Long> timings = new ConcurrentLinkedQueue<>();
            AtomicInteger errorCount = new AtomicInteger();
            AtomicLongArray successesByClient = new AtomicLongArray(clientCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicBoolean running = new AtomicBoolean(true);

            for (int client = 0; client < clientCount; client++) {
                int clientIndex = client;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    while (running.get()) {
                        long start = System.nanoTime();
                        try {
                            ResponseEntity<Map> response = post(
                                    "/v1/reservations",
                                    API_KEY_SECRET_A,
                                    fanoutReservationBody(isolated, clientIndex));
                            Map body = response.getBody();
                            if (response.getStatusCode().value() != 200
                                    || body == null
                                    || body.get("reservation_id") == null
                                    || !Set.of("ALLOW", "ALLOW_WITH_CAPS")
                                    .contains(body.get("decision"))) {
                                errorCount.incrementAndGet();
                                continue;
                            }
                            timings.add(System.nanoTime() - start);
                            successesByClient.incrementAndGet(clientIndex);
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                });
            }

            startLatch.countDown();
            Thread.sleep(MEASURE_DURATION_MS);
            running.set(false);
            executor.shutdown();
            boolean terminated = executor.awaitTermination(60, TimeUnit.SECONDS);
            if (!terminated) {
                executor.shutdownNow();
                terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            }
            assertThat(terminated)
                    .as("All fan-out workers terminated before ledger reconciliation")
                    .isTrue();

            long[] sorted = timings.stream().mapToLong(Long::longValue).sorted().toArray();
            int totalOps = sorted.length;
            int errors = errorCount.get();
            int totalAttempts = totalOps + errors;
            double errorRate = totalAttempts > 0
                    ? errors * 100.0 / totalAttempts
                    : 0.0;
            double opsPerSec = totalOps / (MEASURE_DURATION_MS / 1000.0);
            long ledgerMismatches = countFanoutLedgerMismatches(
                    isolated, successesByClient, totalOps, clientCount);

            FanoutResult result;
            if (totalOps > 0) {
                result = new FanoutResult(
                        isolated ? "isolated" : "shared",
                        clientCount,
                        totalOps,
                        opsPerSec,
                        p(sorted, 50),
                        p(sorted, 95),
                        p(sorted, 99),
                        sorted[0],
                        sorted[sorted.length - 1],
                        errors,
                        errorRate,
                        ledgerMismatches);
            } else {
                result = new FanoutResult(
                        isolated ? "isolated" : "shared",
                        clientCount,
                        0, 0, 0, 0, 0, 0, 0,
                        errors, errorRate, ledgerMismatches);
            }
            synchronized (FANOUT_RESULTS) {
                FANOUT_RESULTS.add(result);
            }

            System.out.printf(
                    "[Fanout] %s %d clients: %d reserves in %ds = %.1f reserves/s  "
                            + "p50=%.1fms  p95=%.1fms  p99=%.1fms  "
                            + "errors=%d  error_rate=%.3f%%  ledger_mismatches=%d%n",
                    result.shape, result.clients, result.totalOps,
                    MEASURE_DURATION_MS / 1000, result.opsPerSec,
                    result.p50 / 1_000_000.0, result.p95 / 1_000_000.0,
                    result.p99 / 1_000_000.0, result.errors,
                    result.errorRatePercent, result.ledgerMismatches);

            assertThat(errorRate)
                    .as("Reserve error rate for %s %d-client fan-out",
                            result.shape, result.clients)
                    .isLessThan(MAX_ERROR_RATE_PERCENT);
            assertThat(totalOps)
                    .as("Successful reserves for %s %d-client fan-out",
                            result.shape, result.clients)
                    .isGreaterThan(0);
            assertThat(ledgerMismatches)
                    .as("Ledger mismatches for %s %d-client fan-out",
                            result.shape, result.clients)
                    .isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private void warmUpFanout(boolean isolated, int clientCount)
            throws Exception {
        int warmupOps = Math.max(WARMUP_OPS, clientCount);
        int warmupConcurrency =
                Math.min(clientCount, MAX_WARMUP_CONCURRENCY);
        ExecutorService executor =
                Executors.newFixedThreadPool(warmupConcurrency);
        try {
            List<Future<ResponseEntity<Map>>> responses =
                    new ArrayList<>(warmupOps);
            for (int operation = 0; operation < warmupOps; operation++) {
                int clientIndex = operation % clientCount;
                responses.add(executor.submit(() -> post(
                        "/v1/reservations",
                        API_KEY_SECRET_A,
                        fanoutReservationBody(isolated, clientIndex))));
            }
            executor.shutdown();
            for (Future<ResponseEntity<Map>> future : responses) {
                ResponseEntity<Map> response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).containsKey("reservation_id");
            }
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("Fan-out warmup workers terminated")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private void prepareFanoutBudgets(boolean isolated, int clientCount) {
        try (var jedis = jedisPool.getResource()) {
            if (!isolated) {
                seedBudget(jedis, TENANT_A, "TOKENS", FANOUT_ALLOCATION);
                return;
            }
            jedis.del("budget:tenant:" + TENANT_A + ":TOKENS");
            for (int client = 0; client < clientCount; client++) {
                seedScopeBudget(
                        jedis,
                        fanoutScope(client),
                        "TOKENS",
                        FANOUT_ALLOCATION,
                        0);
            }
        }
    }

    private Map<String, Object> fanoutReservationBody(boolean isolated, int client) {
        Map<String, Object> body = reservationBody(
                TENANT_A, FANOUT_RESERVE_AMOUNT);
        if (isolated) {
            body.put("subject", Map.of(
                    "tenant", TENANT_A,
                    "agent", fanoutAgent(client)));
        }
        return body;
    }

    private long countFanoutLedgerMismatches(
            boolean isolated,
            AtomicLongArray successesByClient,
            int totalOps,
            int clientCount) {
        try (var jedis = jedisPool.getResource()) {
            if (!isolated) {
                long actual = Long.parseLong(jedis.hget(
                        "budget:tenant:" + TENANT_A + ":TOKENS",
                        "reserved"));
                return actual == totalOps * FANOUT_RESERVE_AMOUNT ? 0 : 1;
            }
            long mismatches = 0;
            for (int client = 0; client < clientCount; client++) {
                long actual = Long.parseLong(jedis.hget(
                        "budget:" + fanoutScope(client) + ":TOKENS",
                        "reserved"));
                long expected = successesByClient.get(client)
                        * FANOUT_RESERVE_AMOUNT;
                if (actual != expected) {
                    mismatches++;
                }
            }
            return mismatches;
        }
    }

    private static String fanoutAgent(int client) {
        return "fanout-" + client;
    }

    private static String fanoutScope(int client) {
        return "tenant:" + TENANT_A + "/agent:" + fanoutAgent(client);
    }

    private static List<Integer> configuredFanoutClientLevels() {
        String configured = System.getProperty("benchmark.fanout.clients");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_FANOUT_CLIENT_LEVELS;
        }
        try {
            List<Integer> levels = Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .toList();
            if (levels.isEmpty() || levels.stream().anyMatch(level -> level <= 0)) {
                throw new IllegalArgumentException(
                        "benchmark.fanout.clients values must be positive");
            }
            return levels;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "benchmark.fanout.clients must be a comma-separated list of integers",
                    e);
        }
    }

    private static long p(long[] sorted, int percentile) {
        return sorted[percentileIndex(sorted.length, percentile)];
    }

    private static int percentileIndex(int length, int percentile) {
        return Math.min((int) Math.ceil(percentile / 100.0 * length) - 1, length - 1);
    }
}
