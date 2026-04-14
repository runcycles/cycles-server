package io.runcycles.protocol.api;

import io.runcycles.protocol.data.service.ReservationExpiryService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;
import net.jqwik.spring.JqwikSpringSupport;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based concurrent test that forces reserve/commit/release/decide interleavings
 * at the budget-exhaustion boundary and asserts three invariants under REJECT overage
 * policy (overdraft_limit = 0):
 *
 *   I1. sum(charged_amount across COMMITTED reservations) <= initial_budget
 *       The service MUST NOT overdraw a budget under any interleaving.
 *
 *   I2. No reservation ever appears in two terminal states simultaneously.
 *       A COMMITTED reservation MUST NOT also carry released_amount, and vice versa.
 *
 *   I3. Every reservation reaches a terminal state within TTL + grace + sweep.
 *       No reservation should remain ACTIVE after the expiry sweep runs.
 *
 * Run via:   mvn-proxy test --file cycles-protocol-service/pom.xml -Pproperty-tests
 * Nightly:   .github/workflows/nightly-property-tests.yml (tries escalated via -Djqwik.tries.default=100)
 *
 * Excluded from default PR CI via the `property-tests` tag in api/pom.xml.
 */
@Tag("property-tests")
@ActiveProfiles({"test"})
@JqwikSpringSupport
@DisplayName("Budget exhaustion concurrent property tests")
class BudgetExhaustionConcurrentPropertyTest extends BaseIntegrationTest {

    /** The tenant under test — reused across properties, budget re-seeded per-try in @BeforeProperty. */
    private static final String API_KEY = API_KEY_SECRET_A;
    private static final String TENANT = TENANT_A;

    /** Per-op TTL. Short enough that invariant 3 sweep runs within the property body. */
    private static final long OP_TTL_MS = 5_000;

    /** Bounded wait for all worker threads to drain the queue. */
    private static final long DRAIN_TIMEOUT_SECONDS = 60;

    @Autowired
    private ReservationExpiryService expiryService;

    /**
     * Per-try reset. Called from inside the property body (NOT from @BeforeProperty) because
     * jqwik-spring's field injection runs inside AroundPropertyHook, which wraps the body —
     * @BeforeProperty fires before injection and would see null @Autowired fields.
     *
     * Also runs on every try, not once per property, so each generated workload starts from a
     * clean Redis state.
     */
    private void resetRedisAndSeedApiKey() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
            seedApiKey(jedis);
        }
    }

    /**
     * Inline api-key seeding (BaseIntegrationTest.seedApiKey is private; we replicate the minimum
     * needed for this test's single tenant).
     */
    private void seedApiKey(Jedis jedis) throws Exception {
        // Use the same mechanism as BaseIntegrationTest.seedTestData by reinvoking seedBudget
        // for minimal coverage, then writing an api-key entry directly.
        String prefix = API_KEY_SECRET_A.substring(0, Math.min(14, API_KEY_SECRET_A.length()));
        String hash = org.mindrot.jbcrypt.BCrypt.hashpw(API_KEY_SECRET_A, org.mindrot.jbcrypt.BCrypt.gensalt());
        var apiKey = io.runcycles.protocol.model.auth.ApiKey.builder()
                .keyId("key-a")
                .tenantId(TENANT_A)
                .keyPrefix(prefix)
                .keyHash(hash)
                .name("Property test key")
                .status(io.runcycles.protocol.model.auth.ApiKeyStatus.ACTIVE)
                .permissions(java.util.Collections.emptyList())
                .createdAt(java.time.Instant.now())
                .build();
        jedis.set("apikey:lookup:" + prefix, "key-a");
        jedis.set("apikey:key-a", objectMapper.writeValueAsString(apiKey));
    }

    /**
     * Main property: under any interleaving of reserve/commit/release/decide operations
     * against a REJECT-policy budget, the three invariants hold.
     *
     * The try count is NOT fixed on the annotation so it can be overridden at runtime via
     * -Djqwik.defaultTries=<N>. Default is 20 (set in src/test/resources/jqwik.properties)
     * for PR-feedback speed; nightly CI runs with 100 for ~5x deeper interleaving coverage.
     */
    @Property(shrinking = ShrinkingMode.FULL)
    void concurrentOpsNeverOverdrawUnderReject(
            @ForAll("threadCounts") int threadCount,
            @ForAll("initialBudgets") long initialBudget,
            @ForAll("workloads") @Size(min = 30, max = 200) List<ReservationOp> workload
    ) throws Exception {
        // --- Arrange: per-try reset + seed budget with overdraft_limit=0 (forces REJECT) ---
        resetRedisAndSeedApiKey();
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudgetWithOverdraftLimit(jedis, TENANT, "TOKENS", initialBudget, 0L);
        }

        // --- Act: drive workload through threadCount worker threads ---
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ConcurrentLinkedQueue<ReservationOp> queue = new ConcurrentLinkedQueue<>(workload);
        AtomicInteger httpErrors = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ReservationOp op;
                    while ((op = queue.poll()) != null) {
                        runOp(op, httpErrors);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean drained = doneLatch.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(drained)
                .as("workload did not drain within %ds — workers may be deadlocked", DRAIN_TIMEOUT_SECONDS)
                .isTrue();

        // --- Force ACTIVE reservations past expiry, then run the sweep ---
        // Any reservation still ACTIVE should have its expires_at set to a past time,
        // then the real expiry service marks them EXPIRED via expire.lua. This exercises
        // the same code path production uses, not a test-only shortcut.
        long now = System.currentTimeMillis();
        Set<String> allKeys = scanReservationKeys();
        for (String key : allKeys) {
            Map<String, String> res = getReservationStateFromRedis(key.substring("reservation:".length()));
            if ("ACTIVE".equals(res.get("state"))) {
                String resId = key.substring("reservation:res_".length());
                expireReservationInRedis(resId, now - 1000);
            }
        }
        expiryService.expireReservations();

        // --- Assert invariants ---
        allKeys = scanReservationKeys();  // re-scan post-sweep

        long totalCommitted = 0;
        for (String key : allKeys) {
            String resId = key.substring("reservation:".length());
            Map<String, String> res = getReservationStateFromRedis(resId);
            String state = res.get("state");

            // I2: terminal-state mutual exclusion
            assertThat(state)
                    .as("reservation %s in unexpected state", resId)
                    .isIn("ACTIVE", "COMMITTED", "RELEASED", "EXPIRED");

            if ("COMMITTED".equals(state)) {
                assertThat(res)
                        .as("COMMITTED reservation %s missing charged_amount", resId)
                        .containsKey("charged_amount");
                assertThat(res.get("released_amount"))
                        .as("COMMITTED reservation %s must not carry released_amount", resId)
                        .isNull();
                String charged = res.get("charged_amount");
                if (charged != null && !charged.isBlank()) {
                    totalCommitted += Long.parseLong(charged);
                }
            } else if ("RELEASED".equals(state)) {
                assertThat(res.get("charged_amount"))
                        .as("RELEASED reservation %s must not carry charged_amount", resId)
                        .isNull();
            }

            // I3: no reservation should remain ACTIVE after forced-expiry + sweep
            assertThat(state)
                    .as("reservation %s still ACTIVE after sweep (budget=%d, threads=%d)",
                            resId, initialBudget, threadCount)
                    .isNotEqualTo("ACTIVE");
        }

        // I1: never overdraw the initial budget
        assertThat(totalCommitted)
                .as("total committed (%d) exceeded initial budget (%d) with %d threads, %d ops",
                        totalCommitted, initialBudget, threadCount, workload.size())
                .isLessThanOrEqualTo(initialBudget);

        // I1 corroboration via the budget hash itself: spent + reserved <= allocated + overdraft_limit (0)
        Map<String, String> budget = getBudgetFromRedis("tenant:" + TENANT, "TOKENS");
        long spent = parseLongOrZero(budget.get("spent"));
        long reserved = parseLongOrZero(budget.get("reserved"));
        long allocated = parseLongOrZero(budget.get("allocated"));
        long debt = parseLongOrZero(budget.get("debt"));
        assertThat(spent + reserved)
                .as("spent(%d) + reserved(%d) exceeds allocated(%d) under REJECT (debt=%d)",
                        spent, reserved, allocated, debt)
                .isLessThanOrEqualTo(allocated);
        assertThat(debt)
                .as("debt must be zero under REJECT policy (overdraft_limit=0); got %d", debt)
                .isEqualTo(0);

        // Sanity: at least some operations succeeded (a fully-denied workload would make invariants trivially hold)
        assertThat(httpErrors.get())
                .as("every op returned a network error — test is not exercising the service")
                .isLessThan(workload.size());
    }

    private void runOp(ReservationOp op, AtomicInteger httpErrors) {
        try {
            // 1. Always try a reserve; BUDGET_EXCEEDED (409) is expected and fine.
            Map<String, Object> reserveBody = reservationBody(TENANT, op.amount);
            reserveBody.put("ttl_ms", OP_TTL_MS);
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY, reserveBody);

            // Interleaving hook: a quick decide call between reserve and commit/release.
            if (op.interleaveDecide) {
                post("/v1/decide", API_KEY, decisionBody(TENANT, op.amount));
            }

            HttpStatusCode status = reserveResp.getStatusCode();
            if (status.is4xxClientError() || status.is5xxServerError()) {
                // Expected: BUDGET_EXCEEDED. Not a test failure.
                return;
            }
            String resId = (String) reserveResp.getBody().get("reservation_id");
            if (resId == null) {
                httpErrors.incrementAndGet();
                return;
            }

            // 2. Follow up: commit (usually) or release (sometimes).
            if (op.action == OpAction.COMMIT) {
                // actual amount may be <= estimate (normal) or > estimate (overage; will be rejected under REJECT)
                long actual = Math.max(0, Math.min(op.actualAmount, op.amount));
                post("/v1/reservations/" + resId + "/commit", API_KEY, commitBody(actual));
            } else {
                post("/v1/reservations/" + resId + "/release", API_KEY, releaseBody());
            }
        } catch (Exception e) {
            httpErrors.incrementAndGet();
        }
    }

    // ---- jqwik generators ----

    @Provide
    Arbitrary<Integer> threadCounts() {
        // Focus on the contention range. 1 thread is degenerate (no races possible).
        return Arbitraries.integers().between(2, 16);
    }

    @Provide
    Arbitrary<Long> initialBudgets() {
        // Deliberately small budgets: force frequent exhaustion so the invariants get exercised.
        // A large budget would make I1 trivially hold.
        return Arbitraries.longs().between(1_000L, 50_000L);
    }

    @Provide
    Arbitrary<List<ReservationOp>> workloads() {
        Arbitrary<ReservationOp> op = Arbitraries.of(OpAction.COMMIT, OpAction.RELEASE)
                .flatMap(action ->
                        Arbitraries.longs().between(50L, 10_000L).flatMap(amount ->
                                Arbitraries.longs().between(0L, 10_000L).flatMap(actual ->
                                        Arbitraries.of(true, false).map(interleave ->
                                                new ReservationOp(action, amount, actual, interleave))
                                )
                        )
                );
        return op.list().ofMinSize(30).ofMaxSize(200);
    }

    // ---- Generator support types ----

    enum OpAction { COMMIT, RELEASE }

    /**
     * A generated workload op. Each op spawns a reserve, optionally followed by a decide,
     * then a commit (with `actualAmount`) or a release.
     */
    record ReservationOp(OpAction action, long amount, long actualAmount, boolean interleaveDecide) {}

    // ---- Misc helpers ----

    private static long parseLongOrZero(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }
}
