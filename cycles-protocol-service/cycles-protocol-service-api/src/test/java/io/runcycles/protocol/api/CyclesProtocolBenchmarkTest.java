package io.runcycles.protocol.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance benchmarks for Cycles Protocol operations.
 *
 * Measures end-to-end HTTP latency (Spring Boot + Jedis + Redis + Lua)
 * for each operation and reports p50/p95/p99/min/max percentiles.
 *
 * Results are environment-dependent (CI vs local, container overhead, etc.).
 * Use these numbers as relative guidance, not absolute SLA targets.
 *
 * Run separately: mvn test -Dgroups=benchmark
 */
@DisplayName("Performance Benchmarks")
@Tag("benchmark")
class CyclesProtocolBenchmarkTest extends BaseIntegrationTest {

    private static final int WARMUP_ITERATIONS = 50;
    private static final int MEASURE_ITERATIONS = 200;
    private static Level previousRootLevel;

    // Collect all results for a summary table at the end
    private static final List<BenchmarkResult> ALL_RESULTS = new ArrayList<>();

    @BeforeAll
    static void quietLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        previousRootLevel = root.getLevel();
        root.setLevel(Level.WARN);
    }

    record BenchmarkResult(String name, long p50, long p95, long p99, long min, long max, long mean) {}

    @AfterAll
    static void printSummary() {
        // Restore log level
        if (previousRootLevel != null) {
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(previousRootLevel);
        }
        if (ALL_RESULTS.isEmpty()) return;

        System.out.println();
        System.out.println("+---------------------------+--------+--------+--------+--------+--------+--------+");
        System.out.println("| Operation                 |  p50   |  p95   |  p99   |  min   |  max   |  mean  |");
        System.out.println("+---------------------------+--------+--------+--------+--------+--------+--------+");
        for (BenchmarkResult r : ALL_RESULTS) {
            System.out.printf("| %-25s | %5.1fms| %5.1fms| %5.1fms| %5.1fms| %5.1fms| %5.1fms|%n",
                    r.name,
                    r.p50 / 1_000_000.0, r.p95 / 1_000_000.0, r.p99 / 1_000_000.0,
                    r.min / 1_000_000.0, r.max / 1_000_000.0, r.mean / 1_000_000.0);
        }
        System.out.println("+---------------------------+--------+--------+--------+--------+--------+--------+");
        System.out.println("  Iterations: " + MEASURE_ITERATIONS + " (after " + WARMUP_ITERATIONS + " warmup)");
        System.out.println();
    }

    // ---- Individual Operation Benchmarks ----

    @Test
    @DisplayName("Reserve (POST /v1/reservations)")
    void benchmarkReserve() {
        long[] timings = runBenchmark("Reserve", () -> {
            Map<String, Object> body = reservationBody(TENANT_A, 100);
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Reserve", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("Commit (POST /v1/reservations/{id}/commit)")
    void benchmarkCommit() {
        // Pre-create reservations for committing
        List<String> reservationIds = new ArrayList<>();
        for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
            reservationIds.add(createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100));
        }

        int[] idx = {0};
        long[] timings = runBenchmark("Commit", () -> {
            String resId = reservationIds.get(idx[0]++);
            ResponseEntity<Map> resp = post("/v1/reservations/" + resId + "/commit",
                    API_KEY_SECRET_A, commitBody(80));
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Commit", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("Release (POST /v1/reservations/{id}/release)")
    void benchmarkRelease() {
        List<String> reservationIds = new ArrayList<>();
        for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
            reservationIds.add(createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100));
        }

        int[] idx = {0};
        long[] timings = runBenchmark("Release", () -> {
            String resId = reservationIds.get(idx[0]++);
            ResponseEntity<Map> resp = post("/v1/reservations/" + resId + "/release",
                    API_KEY_SECRET_A, releaseBody());
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Release", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("Extend (POST /v1/reservations/{id}/extend)")
    void benchmarkExtend() {
        // Pre-create reservations (each can only be extended max_extensions times)
        List<String> reservationIds = new ArrayList<>();
        for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
            reservationIds.add(createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100));
        }

        int[] idx = {0};
        long[] timings = runBenchmark("Extend", () -> {
            String resId = reservationIds.get(idx[0]++);
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("extend_by_ms", 10000);
            ResponseEntity<Map> resp = post("/v1/reservations/" + resId + "/extend",
                    API_KEY_SECRET_A, body);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Extend", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("Decide (POST /v1/decide)")
    void benchmarkDecide() {
        long[] timings = runBenchmark("Decide", () -> {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A,
                    decisionBody(TENANT_A, 100));
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Decide", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("Event (POST /v1/events)")
    void benchmarkEvent() {
        long[] timings = runBenchmark("Event", () -> {
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_A,
                    eventBody(TENANT_A, 50));
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
        });
        record(new BenchmarkResult("Event", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    // ---- Composite Lifecycle Benchmarks ----

    @Test
    @DisplayName("Reserve → Commit (full lifecycle)")
    void benchmarkFullLifecycle() {
        long[] timings = runBenchmark("Reserve→Commit", () -> {
            Map<String, Object> body = reservationBody(TENANT_A, 100);
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String resId = (String) reserveResp.getBody().get("reservation_id");

            ResponseEntity<Map> commitResp = post("/v1/reservations/" + resId + "/commit",
                    API_KEY_SECRET_A, commitBody(80));
            assertThat(commitResp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Reserve→Commit", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("Reserve → Release (cancel path)")
    void benchmarkReserveRelease() {
        long[] timings = runBenchmark("Reserve→Release", () -> {
            Map<String, Object> body = reservationBody(TENANT_A, 100);
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(reserveResp.getStatusCode().value()).isEqualTo(200);
            String resId = (String) reserveResp.getBody().get("reservation_id");

            ResponseEntity<Map> releaseResp = post("/v1/reservations/" + resId + "/release",
                    API_KEY_SECRET_A, releaseBody());
            assertThat(releaseResp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Reserve→Release", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    // ---- Benchmark Infrastructure ----

    private long[] runBenchmark(String name, Runnable operation) {
        // Warm up (JIT, connection pool, EVALSHA script cache)
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            operation.run();
        }

        // Measure
        long[] timings = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            operation.run();
            timings[i] = System.nanoTime() - start;
        }

        Arrays.sort(timings);

        System.out.printf("[Benchmark] %-20s  p50=%.1fms  p95=%.1fms  p99=%.1fms  min=%.1fms  max=%.1fms%n",
                name,
                timings[percentileIndex(50)] / 1_000_000.0,
                timings[percentileIndex(95)] / 1_000_000.0,
                timings[percentileIndex(99)] / 1_000_000.0,
                timings[0] / 1_000_000.0,
                timings[timings.length - 1] / 1_000_000.0);

        return timings;
    }

    private static long p(long[] sorted, int percentile) {
        return sorted[percentileIndex(percentile)];
    }

    private static int percentileIndex(int percentile) {
        return Math.min((int) Math.ceil(percentile / 100.0 * MEASURE_ITERATIONS) - 1, MEASURE_ITERATIONS - 1);
    }

    private static long mean(long[] values) {
        return (long) LongStream.of(values).average().orElse(0);
    }

    private static synchronized void record(BenchmarkResult result) {
        ALL_RESULTS.add(result);
    }
}
