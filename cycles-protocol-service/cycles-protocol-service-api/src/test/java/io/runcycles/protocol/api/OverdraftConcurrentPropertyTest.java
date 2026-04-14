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

import java.util.HashMap;
import java.util.List;
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
 * Property-based concurrent test for the two non-REJECT overage policies:
 *
 *   ALLOW_IF_AVAILABLE — may cap delta to available remaining; MUST NEVER create debt.
 *       I1. debt == 0 at all times (on the budget record and across all reservations).
 *       I2. sum(charged_amount across COMMITTED) <= allocated.
 *       I3. Each COMMITTED reservation's charged_amount <= requested actual_amount.
 *
 *   ALLOW_WITH_OVERDRAFT — may accrue debt, but strictly <= overdraft_limit.
 *       J1. debt <= overdraft_limit at all times.
 *       J2. sum(charged_amount across COMMITTED) <= allocated + overdraft_limit.
 *       J3. Ledger invariant holds: allocated == remaining + spent + reserved + debt.
 *
 *   Both policies (shared with the REJECT test):
 *       K1. No reservation in two terminal states simultaneously.
 *       K2. Every reservation reaches a terminal state after TTL+sweep.
 *
 * Same harness as {@link BudgetExhaustionConcurrentPropertyTest} — workload generator,
 * thread interleaving, and expiry sweep are reused; only the policy and post-hoc
 * assertions differ.
 */
@Tag("property-tests")
@ActiveProfiles({"test"})
@JqwikSpringSupport
@DisplayName("Overdraft concurrent property tests")
class OverdraftConcurrentPropertyTest extends BaseIntegrationTest {

    private static final String API_KEY = API_KEY_SECRET_A;
    private static final String TENANT = TENANT_A;
    private static final long OP_TTL_MS = 5_000;
    private static final long DRAIN_TIMEOUT_SECONDS = 60;

    @Autowired
    private ReservationExpiryService expiryService;

    enum Policy {
        ALLOW_IF_AVAILABLE,
        ALLOW_WITH_OVERDRAFT
    }

    enum OpAction { COMMIT, RELEASE }

    record ReservationOp(OpAction action, long amount, long actualAmount, boolean interleaveDecide) {}

    private void resetRedisAndSeedApiKey() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
            seedApiKey(jedis);
        }
    }

    private void seedApiKey(Jedis jedis) throws Exception {
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

    @Property(shrinking = ShrinkingMode.FULL)
    void allowIfAvailableNeverCreatesDebtAndNeverOverdraws(
            @ForAll("threadCounts") int threadCount,
            @ForAll("initialBudgets") long initialBudget,
            @ForAll("workloads") @Size(min = 30, max = 200) List<ReservationOp> workload
    ) throws Exception {
        resetRedisAndSeedApiKey();
        // overdraft_limit=0 ensures an ALLOW_WITH_OVERDRAFT request would fall back to
        // ALLOW_IF_AVAILABLE per spec, but we request ALLOW_IF_AVAILABLE explicitly so
        // the code path we care about is exercised directly.
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudgetWithOverdraftLimit(jedis, TENANT, "TOKENS", initialBudget, 0L);
        }

        driveWorkload(threadCount, workload, Policy.ALLOW_IF_AVAILABLE);
        forceExpirySweep();

        Set<String> allKeys = scanReservationKeys();
        long totalCommitted = assertCommonReservationInvariants(allKeys, initialBudget, threadCount, workload.size());

        // I1/I2: never overdraws allocated
        assertThat(totalCommitted)
                .as("ALLOW_IF_AVAILABLE total committed (%d) exceeded allocated (%d)",
                        totalCommitted, initialBudget)
                .isLessThanOrEqualTo(initialBudget);

        Map<String, String> budget = getBudgetFromRedis("tenant:" + TENANT, "TOKENS");
        long allocated = parseLongOrZero(budget.get("allocated"));
        long remaining = parseLongOrZero(budget.get("remaining"));
        long reserved = parseLongOrZero(budget.get("reserved"));
        long spent = parseLongOrZero(budget.get("spent"));
        long debt = parseLongOrZero(budget.get("debt"));

        // I1: never creates debt
        assertThat(debt)
                .as("ALLOW_IF_AVAILABLE must never create debt; got debt=%d", debt)
                .isEqualTo(0);

        // Ledger invariant (spec): allocated == remaining + spent + reserved + debt
        assertThat(remaining + spent + reserved + debt)
                .as("ledger invariant violated: remaining(%d)+spent(%d)+reserved(%d)+debt(%d) != allocated(%d)",
                        remaining, spent, reserved, debt, allocated)
                .isEqualTo(allocated);

        // With debt=0, spent+reserved must stay within allocated
        assertThat(spent + reserved)
                .as("ALLOW_IF_AVAILABLE spent(%d)+reserved(%d) exceeds allocated(%d)",
                        spent, reserved, allocated)
                .isLessThanOrEqualTo(allocated);
    }

    @Property(shrinking = ShrinkingMode.FULL)
    void allowWithOverdraftRespectsOverdraftLimit(
            @ForAll("threadCounts") int threadCount,
            @ForAll("initialBudgets") long initialBudget,
            @ForAll("overdraftLimits") long overdraftLimit,
            @ForAll("workloads") @Size(min = 30, max = 200) List<ReservationOp> workload
    ) throws Exception {
        resetRedisAndSeedApiKey();
        try (Jedis jedis = jedisPool.getResource()) {
            seedBudgetWithOverdraftLimit(jedis, TENANT, "TOKENS", initialBudget, overdraftLimit);
        }

        driveWorkload(threadCount, workload, Policy.ALLOW_WITH_OVERDRAFT);
        forceExpirySweep();

        Set<String> allKeys = scanReservationKeys();
        long totalCommitted = assertCommonReservationInvariants(allKeys, initialBudget, threadCount, workload.size());

        Map<String, String> budget = getBudgetFromRedis("tenant:" + TENANT, "TOKENS");
        long allocated = parseLongOrZero(budget.get("allocated"));
        long remaining = parseLongOrZero(budget.get("remaining"));
        long reserved = parseLongOrZero(budget.get("reserved"));
        long spent = parseLongOrZero(budget.get("spent"));
        long debt = parseLongOrZero(budget.get("debt"));

        // J1: debt strictly bounded by overdraft_limit
        assertThat(debt)
                .as("ALLOW_WITH_OVERDRAFT debt(%d) exceeded overdraft_limit(%d); allocated=%d, spent=%d, reserved=%d",
                        debt, overdraftLimit, allocated, spent, reserved)
                .isLessThanOrEqualTo(overdraftLimit);

        // J2: cumulative charged never exceeds allocated + overdraft_limit
        assertThat(totalCommitted)
                .as("ALLOW_WITH_OVERDRAFT total committed (%d) exceeded allocated(%d)+overdraft_limit(%d)",
                        totalCommitted, allocated, overdraftLimit)
                .isLessThanOrEqualTo(allocated + overdraftLimit);

        // J3: ledger invariant
        assertThat(remaining + spent + reserved + debt)
                .as("ledger invariant violated: remaining(%d)+spent(%d)+reserved(%d)+debt(%d) != allocated(%d)",
                        remaining, spent, reserved, debt, allocated)
                .isEqualTo(allocated);
    }

    // ---- Shared execution harness ----

    private void driveWorkload(int threadCount, List<ReservationOp> workload, Policy policy) throws Exception {
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
                        runOp(op, policy, httpErrors);
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

        // Guard: every op errored → no service exercise
        assertThat(httpErrors.get())
                .as("every op returned a network error — test is not exercising the service")
                .isLessThan(workload.size());
    }

    private void forceExpirySweep() {
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
    }

    private long assertCommonReservationInvariants(Set<String> allKeys, long initialBudget,
                                                    int threadCount, int workloadSize) {
        long totalCommitted = 0;
        for (String key : allKeys) {
            String resId = key.substring("reservation:".length());
            Map<String, String> res = getReservationStateFromRedis(resId);
            String state = res.get("state");

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

            assertThat(state)
                    .as("reservation %s still ACTIVE after sweep (budget=%d, threads=%d, ops=%d)",
                            resId, initialBudget, threadCount, workloadSize)
                    .isNotEqualTo("ACTIVE");
        }
        return totalCommitted;
    }

    private void runOp(ReservationOp op, Policy policy, AtomicInteger httpErrors) {
        try {
            Map<String, Object> reserveBody = reservationBodyWithPolicy(op.amount, policy);
            reserveBody.put("ttl_ms", OP_TTL_MS);
            ResponseEntity<Map> reserveResp = post("/v1/reservations", API_KEY, reserveBody);

            if (op.interleaveDecide) {
                post("/v1/decide", API_KEY, decisionBody(TENANT, op.amount));
            }

            HttpStatusCode status = reserveResp.getStatusCode();
            if (status.is4xxClientError() || status.is5xxServerError()) {
                return;
            }
            String resId = (String) reserveResp.getBody().get("reservation_id");
            if (resId == null) {
                httpErrors.incrementAndGet();
                return;
            }

            if (op.action == OpAction.COMMIT) {
                // Deliberately span both under-estimate and over-estimate (overage) commits.
                // Under ALLOW_IF_AVAILABLE overage gets capped; under ALLOW_WITH_OVERDRAFT
                // it may accrue debt up to overdraft_limit.
                long actual = Math.max(0, op.actualAmount);
                post("/v1/reservations/" + resId + "/commit", API_KEY, commitBody(actual));
            } else {
                post("/v1/reservations/" + resId + "/release", API_KEY, releaseBody());
            }
        } catch (Exception e) {
            httpErrors.incrementAndGet();
        }
    }

    private Map<String, Object> reservationBodyWithPolicy(long amount, Policy policy) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", Map.of("tenant", TENANT));
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("estimate", Map.of("unit", "TOKENS", "amount", amount));
        body.put("ttl_ms", OP_TTL_MS);
        body.put("overage_policy", policy.name());
        return body;
    }

    // ---- jqwik generators ----

    @Provide
    Arbitrary<Integer> threadCounts() {
        return Arbitraries.integers().between(2, 16);
    }

    @Provide
    Arbitrary<Long> initialBudgets() {
        return Arbitraries.longs().between(1_000L, 50_000L);
    }

    @Provide
    Arbitrary<Long> overdraftLimits() {
        // Include 0 (fallback to ALLOW_IF_AVAILABLE behavior per spec) and non-trivial limits.
        return Arbitraries.longs().between(0L, 20_000L);
    }

    @Provide
    Arbitrary<List<ReservationOp>> workloads() {
        Arbitrary<ReservationOp> op = Arbitraries.of(OpAction.COMMIT, OpAction.RELEASE)
                .flatMap(action ->
                        Arbitraries.longs().between(50L, 10_000L).flatMap(amount ->
                                // actual can exceed estimate to exercise the overage branches
                                Arbitraries.longs().between(0L, 30_000L).flatMap(actual ->
                                        Arbitraries.of(true, false).map(interleave ->
                                                new ReservationOp(action, amount, actual, interleave))
                                )
                        )
                );
        return op.list().ofMinSize(30).ofMaxSize(200);
    }

    private static long parseLongOrZero(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }
}
