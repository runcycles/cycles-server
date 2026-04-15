package io.runcycles.protocol.api;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.Tag;
import net.jqwik.spring.JqwikSpringSupport;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for multi-scope spend attribution under contention.
 *
 * Builds a subject spanning 1–6 hierarchy levels (tenant → workspace → app → workflow
 * → agent → toolset), seeds a budget at every derived scope, then fires concurrent
 * reserve+commit pairs using that same subject. Because every reservation touches every
 * budgeted scope, after all commits land we expect:
 *
 *   S1. For every seeded scope level, {@code spent[level]} equals the same value across
 *       all levels — no level dropped or double-counted an increment.
 *   S2. {@code spent[level]} == sum of {@code charged_amount} across COMMITTED reservations
 *       (reserves that failed or that were later released contribute 0).
 *   S3. Ledger invariant: {@code allocated == remaining + spent + reserved + debt} at
 *       every level.
 *
 * Complements {@link ScopeAndExpiryIntegrationTest} which validates the single-threaded
 * scalar path.
 */
@Tag("property-tests")
@ActiveProfiles({"test"})
@JqwikSpringSupport
@DisplayName("Multi-scope attribution concurrent property tests")
class ScopeAttributionConcurrentPropertyTest extends BaseIntegrationTest {

    private static final String API_KEY = API_KEY_SECRET_A;
    private static final String TENANT = TENANT_A;
    private static final long OP_TTL_MS = 5_000;
    private static final long DRAIN_TIMEOUT_SECONDS = 60;

    /**
     * Hierarchy order matches ScopeDerivationService. Keys are subject field names.
     */
    private static final String[] HIERARCHY = {
            "tenant", "workspace", "app", "workflow", "agent", "toolset"
    };

    private void resetRedisAndSeedApiKey() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
            seedApiKeyA(jedis);
        }
    }

    private void seedApiKeyA(Jedis jedis) throws Exception {
        String prefix = API_KEY_SECRET_A.substring(0, Math.min(14, API_KEY_SECRET_A.length()));
        String hash = org.mindrot.jbcrypt.BCrypt.hashpw(API_KEY_SECRET_A, org.mindrot.jbcrypt.BCrypt.gensalt());
        var apiKey = io.runcycles.protocol.model.auth.ApiKey.builder()
                .keyId("key-a")
                .tenantId(TENANT_A)
                .keyPrefix(prefix)
                .keyHash(hash)
                .name("Scope property test key")
                .status(io.runcycles.protocol.model.auth.ApiKeyStatus.ACTIVE)
                .permissions(java.util.Collections.emptyList())
                .createdAt(java.time.Instant.now())
                .build();
        jedis.set("apikey:lookup:" + prefix, "key-a");
        jedis.set("apikey:key-a", objectMapper.writeValueAsString(apiKey));
    }

    @Property(shrinking = ShrinkingMode.FULL)
    void spendAttributedConsistentlyAcrossAllLevels(
            @ForAll("scopeDepths") int depth,
            @ForAll("threadCounts") int threadCount,
            @ForAll("commitCounts") int commitCount
    ) throws Exception {
        resetRedisAndSeedApiKey();

        // Build subject + derived scope paths.
        Map<String, String> subject = new LinkedHashMap<>();
        List<String> derivedScopes = new ArrayList<>();
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            String key = HIERARCHY[i];
            // toolset isn't the most natural value to auto-generate; stable names suffice.
            String val = (key.equals("tenant") ? TENANT : key + "-x");
            subject.put(key, val);
            if (path.length() > 0) path.append('/');
            path.append(key).append(':').append(val);
            derivedScopes.add(path.toString());
        }

        // Seed budgets at every derived scope with generous headroom so no commit is denied.
        long allocatedPerLevel = 10_000_000L;
        try (Jedis jedis = jedisPool.getResource()) {
            for (String scope : derivedScopes) {
                seedScopeBudget(jedis, scope, "TOKENS", allocatedPerLevel, 0L);
            }
        }

        // Drive concurrent reserve+commit pairs. Record each successful commit's charged amount.
        long perCommit = 1_000L;
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(commitCount);
        java.util.concurrent.atomic.AtomicLong totalCharged = new java.util.concurrent.atomic.AtomicLong(0);
        AtomicInteger httpErrors = new AtomicInteger(0);

        for (int i = 0; i < commitCount; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    Map<String, Object> body = reservationBodyForSubject(subject, perCommit);
                    ResponseEntity<Map> reserve = post("/v1/reservations", API_KEY, body);
                    if (!reserve.getStatusCode().is2xxSuccessful()) {
                        httpErrors.incrementAndGet();
                        return;
                    }
                    String resId = (String) reserve.getBody().get("reservation_id");
                    if (resId == null) {
                        httpErrors.incrementAndGet();
                        return;
                    }
                    ResponseEntity<Map> commit = post(
                            "/v1/reservations/" + resId + "/commit", API_KEY, commitBody(perCommit));
                    if (commit.getStatusCode().is2xxSuccessful()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> charged = (Map<String, Object>) commit.getBody().get("charged");
                        long amt = ((Number) charged.get("amount")).longValue();
                        totalCharged.addAndGet(amt);
                    }
                } catch (Exception e) {
                    httpErrors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean drained = done.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        exec.shutdown();
        exec.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(drained).as("workload did not drain within %ds", DRAIN_TIMEOUT_SECONDS).isTrue();
        assertThat(httpErrors.get())
                .as("every op errored — test not exercising service")
                .isLessThan(commitCount);

        // ---- Assertions ----
        long expectedSpent = totalCharged.get();
        Long firstSpent = null;
        for (String scope : derivedScopes) {
            Map<String, String> bal = getBudgetFromRedis(scope, "TOKENS");
            long allocated = parseLongOrZero(bal.get("allocated"));
            long remaining = parseLongOrZero(bal.get("remaining"));
            long reserved = parseLongOrZero(bal.get("reserved"));
            long spent    = parseLongOrZero(bal.get("spent"));
            long debt     = parseLongOrZero(bal.get("debt"));

            // S2: spent matches the committed-sum we observed from API responses
            assertThat(spent)
                    .as("scope=%s spent(%d) != Σcharged(%d) across %d commits on depth %d",
                            scope, spent, expectedSpent, commitCount, depth)
                    .isEqualTo(expectedSpent);

            // S1: same spent value everywhere (no level dropped an increment)
            if (firstSpent == null) firstSpent = spent;
            else assertThat(spent)
                    .as("scope=%s spent(%d) diverged from first level (%d)", scope, spent, firstSpent)
                    .isEqualTo(firstSpent);

            // S3: ledger invariant at every level
            assertThat(remaining + spent + reserved + debt)
                    .as("ledger invariant violated at %s: r(%d)+s(%d)+r(%d)+d(%d) != alloc(%d)",
                            scope, remaining, spent, reserved, debt, allocated)
                    .isEqualTo(allocated);
        }
    }

    private Map<String, Object> reservationBodyForSubject(Map<String, String> subject, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", subject);
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("estimate", Map.of("unit", "TOKENS", "amount", amount));
        body.put("ttl_ms", OP_TTL_MS);
        body.put("overage_policy", "REJECT");
        return body;
    }

    // ---- Generators ----

    @Provide
    Arbitrary<Integer> scopeDepths() {
        return Arbitraries.integers().between(1, HIERARCHY.length);
    }

    @Provide
    Arbitrary<Integer> threadCounts() {
        return Arbitraries.integers().between(2, 12);
    }

    @Provide
    Arbitrary<Integer> commitCounts() {
        return Arbitraries.integers().between(10, 60);
    }

    private static long parseLongOrZero(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }
}
