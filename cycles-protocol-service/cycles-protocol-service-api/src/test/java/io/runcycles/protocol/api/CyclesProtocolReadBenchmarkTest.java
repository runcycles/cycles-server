package io.runcycles.protocol.api;

import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance benchmarks for Cycles Protocol read/query operations.
 *
 * Measures end-to-end HTTP latency for GET endpoints and decide (pipelined).
 * Complements CyclesProtocolBenchmarkTest which covers mutation operations.
 *
 * Run separately: mvn test -Pbenchmark
 */
@DisplayName("Read Path Benchmarks")
@Tag("benchmark")
@ActiveProfiles({"test", "benchmark"})
class CyclesProtocolReadBenchmarkTest extends BaseIntegrationTest {

    private static final int WARMUP_ITERATIONS = 50;
    private static final int MEASURE_ITERATIONS = 200;

    private static final List<BenchmarkResult> ALL_RESULTS = new ArrayList<>();

    record BenchmarkResult(String name, long p50, long p95, long p99, long min, long max, long mean) {}

    @AfterAll
    static void printSummary() {
        if (ALL_RESULTS.isEmpty()) return;

        System.out.println();
        System.out.println("+---------------------------+--------+--------+--------+--------+--------+--------+");
        System.out.println("| Read Operation            |  p50   |  p95   |  p99   |  min   |  max   |  mean  |");
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

    @Test
    @DisplayName("GET /v1/reservations/{id}")
    void benchmarkGetReservation() {
        // Pre-create a reservation to fetch
        String resId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

        long[] timings = runBenchmark("GET reservation", () -> {
            ResponseEntity<Map> resp = get("/v1/reservations/" + resId, API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("GET reservation", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("GET /v1/reservations (list)")
    void benchmarkListReservations() {
        // Pre-create some reservations
        for (int i = 0; i < 10; i++) {
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
        }

        long[] timings = runBenchmark("LIST reservations", () -> {
            ResponseEntity<Map> resp = get("/v1/reservations?limit=10", API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("LIST reservations", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("GET /v1/balances")
    void benchmarkGetBalances() {
        long[] timings = runBenchmark("GET balances", () -> {
            ResponseEntity<Map> resp = get("/v1/balances", API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("GET balances", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    @Test
    @DisplayName("POST /v1/decide (pipelined)")
    void benchmarkDecide() {
        long[] timings = runBenchmark("Decide (pipelined)", () -> {
            Map<String, Object> body = decideBody(TENANT_A, 100);
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_A, body);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        });
        record(new BenchmarkResult("Decide (pipelined)", p(timings, 50), p(timings, 95), p(timings, 99),
                timings[0], timings[timings.length - 1], mean(timings)));
    }

    // ---- Benchmark Infrastructure ----

    private long[] runBenchmark(String name, Runnable operation) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            operation.run();
        }

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

    private Map<String, Object> decideBody(String tenant, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", Map.of("tenant", tenant));
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("estimate", Map.of("unit", "TOKENS", "amount", amount));
        return body;
    }
}
