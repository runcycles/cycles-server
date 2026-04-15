package io.runcycles.protocol.api;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for audit-log completeness on the admin-driven release path (v0.1.25.8).
 *
 * For each generated workload — a mix of admin releases against reservations that are in
 * different states — assert:
 *
 *   A1. Every successful admin release produces exactly one audit entry in Redis.
 *       No duplicates, no drops.
 *   A2. Every audit entry carries the required fields: log_id, tenant_id, operation,
 *       resource_type, resource_id, status, and metadata.actor_type = "admin_on_behalf_of".
 *   A3. Every entry is indexed in BOTH zsets: {@code audit:logs:{tenant}} and
 *       {@code audit:logs:_all}, with the same score (timestamp millis).
 *   A4. Entries are ordered correctly by timestamp in both indexes (no reordering,
 *       no score skew between the two zsets).
 *
 * The non-admin release path deliberately does NOT write audit entries (tenant mutations
 * aren't admin-audited), so the workload is admin-heavy — mirrors the real concern: a
 * compliance-grade log for admin overrides.
 */
@Tag("property-tests")
@ActiveProfiles({"test"})
@JqwikSpringSupport
@TestPropertySource(properties = "admin.api-key=audit-test-admin-key")
@DisplayName("Audit-log completeness property tests")
class AuditLogCompletenessPropertyTest extends BaseIntegrationTest {

    private static final String ADMIN_KEY = "audit-test-admin-key";

    enum Target { ACTIVE_OK, ALREADY_COMMITTED, ALREADY_RELEASED }

    record AdminOp(Target target) {}

    private void resetRedisAndSeed() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
            seedApiKeyA(jedis);
            seedBudget(jedis, TENANT_A, "TOKENS", 100_000_000);
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
                .name("Audit property test key")
                .status(io.runcycles.protocol.model.auth.ApiKeyStatus.ACTIVE)
                .permissions(java.util.Collections.emptyList())
                .createdAt(java.time.Instant.now())
                .build();
        jedis.set("apikey:lookup:" + prefix, "key-a");
        jedis.set("apikey:key-a", objectMapper.writeValueAsString(apiKey));
    }

    @Property(shrinking = ShrinkingMode.FULL)
    void auditEntriesAreCompleteAndIndexedCorrectly(
            @ForAll("workloads") @Size(min = 5, max = 20) List<AdminOp> workload
    ) throws Exception {
        resetRedisAndSeed();

        int expectedSuccesses = 0;
        java.util.List<String> successResourceIds = new java.util.ArrayList<>();

        for (AdminOp op : workload) {
            String resId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            // Pre-drive the reservation into the desired state before admin release
            switch (op.target) {
                case ALREADY_COMMITTED -> post("/v1/reservations/" + resId + "/commit",
                        API_KEY_SECRET_A, commitBody(50));
                case ALREADY_RELEASED -> post("/v1/reservations/" + resId + "/release",
                        API_KEY_SECRET_A, releaseBody());
                case ACTIVE_OK -> {}
            }

            ResponseEntity<Map> resp = adminRelease(resId);
            if (resp.getStatusCode().is2xxSuccessful()) {
                expectedSuccesses++;
                successResourceIds.add(resId);
            }
            // ACTIVE_OK should always succeed; the others should fail with 409.
            if (op.target == Target.ACTIVE_OK) {
                assertThat(resp.getStatusCode().is2xxSuccessful())
                        .as("ACTIVE admin-release must succeed; got %s body=%s",
                                resp.getStatusCode(), resp.getBody())
                        .isTrue();
            } else {
                assertThat(resp.getStatusCode().is2xxSuccessful())
                        .as("finalized admin-release must NOT succeed; got 2xx for %s",
                                op.target)
                        .isFalse();
            }
        }

        // ---- Drain and verify audit store ----
        try (Jedis jedis = jedisPool.getResource()) {
            // All: global zset
            List<String> allIds = jedis.zrange("audit:logs:_all", 0, -1);
            List<Double> allScores = allIds.stream()
                    .map(id -> jedis.zscore("audit:logs:_all", id))
                    .collect(Collectors.toList());

            // Per-tenant zset
            List<String> tenantIds = jedis.zrange("audit:logs:" + TENANT_A, 0, -1);

            // A1: count matches successful admin releases
            assertThat(allIds)
                    .as("audit:logs:_all size must equal successful admin-releases (%d); workload=%s",
                            expectedSuccesses, workload)
                    .hasSize(expectedSuccesses);

            // A3a: per-tenant index holds the same set of ids
            assertThat(Set.copyOf(tenantIds))
                    .as("per-tenant audit zset must mirror the global set")
                    .isEqualTo(Set.copyOf(allIds));

            // A4: global zset ordered ascending by score (ZRANGE default)
            for (int i = 1; i < allScores.size(); i++) {
                assertThat(allScores.get(i))
                        .as("audit:logs:_all must be ordered by timestamp (pos %d)", i)
                        .isGreaterThanOrEqualTo(allScores.get(i - 1));
            }

            // A3b: scores in per-tenant zset match the global zset for the same log id
            for (String logId : allIds) {
                Double globalScore = jedis.zscore("audit:logs:_all", logId);
                Double tenantScore = jedis.zscore("audit:logs:" + TENANT_A, logId);
                assertThat(tenantScore)
                        .as("scores must match between tenant and global zsets for %s", logId)
                        .isEqualTo(globalScore);
            }

            // A2: required fields + actor_type marker present on every entry
            // The payload is JSON keyed at audit:log:{log_id}
            Set<String> auditedResourceIds = new java.util.HashSet<>();
            for (String logId : allIds) {
                String json = jedis.get("audit:log:" + logId);
                assertThat(json)
                        .as("audit:log:%s must exist (zset pointed here)", logId)
                        .isNotNull();
                Map<String, Object> entry = objectMapper.readValue(json,
                        new TypeReference<Map<String, Object>>() {});
                assertThat(entry).containsKeys(
                        "log_id", "tenant_id", "operation", "resource_type",
                        "resource_id", "status", "metadata");
                assertThat(entry.get("operation")).isEqualTo("releaseReservation");
                assertThat(entry.get("resource_type")).isEqualTo("reservation");
                assertThat(entry.get("tenant_id")).isEqualTo(TENANT_A);
                @SuppressWarnings("unchecked")
                Map<String, Object> md = (Map<String, Object>) entry.get("metadata");
                assertThat(md.get("actor_type"))
                        .as("spec MUST: actor_type=admin_on_behalf_of")
                        .isEqualTo("admin_on_behalf_of");
                auditedResourceIds.add((String) entry.get("resource_id"));
            }
            // A1 corroboration: every successful admin-release resource_id has an entry,
            // and nothing else does.
            assertThat(auditedResourceIds)
                    .as("audited resource ids must match the set of successful admin releases")
                    .isEqualTo(Set.copyOf(successResourceIds));
        }
    }

    private ResponseEntity<Map> adminRelease(String reservationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-API-Key", ADMIN_KEY);
        return restTemplate.exchange(
                baseUrl() + "/v1/reservations/" + reservationId + "/release",
                HttpMethod.POST,
                new HttpEntity<>(releaseBody(), headers),
                Map.class);
    }

    @Provide
    Arbitrary<List<AdminOp>> workloads() {
        Arbitrary<AdminOp> op = Arbitraries.of(Target.values()).map(AdminOp::new);
        return op.list().ofMinSize(5).ofMaxSize(20);
    }
}
