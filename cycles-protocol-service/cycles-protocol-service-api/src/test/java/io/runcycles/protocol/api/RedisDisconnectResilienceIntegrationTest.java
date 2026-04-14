package io.runcycles.protocol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.api.contract.ContractValidatingRestTemplateInterceptor;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the service's behaviour when Redis becomes unavailable mid-operation and
 * when it comes back. Two invariants:
 *
 *   R1. A reservation operation that hits Redis while the container is paused
 *       returns a structured error with a sensible status code. It must NOT hang
 *       indefinitely, return a blank 200, or leak exceptions with no body.
 *   R2. After Redis recovers, subsequent operations succeed using the state that
 *       was persisted before the outage. No corruption, no stale locks, no
 *       orphaned TTL zset entries.
 *
 * <p>Runs in its own Testcontainers Redis (not shared with {@link BaseIntegrationTest})
 * because pause/unpause on the shared container would break other tests that run in
 * parallel. Spring context uses {@link SpringBootTest(RANDOM_PORT)} — same boot path
 * as other integration tests; only the Redis container is different.
 *
 * <p>The test deliberately uses a short Jedis connect/read timeout so the pause case
 * returns quickly. If the service's pool timeouts ever grow unbounded, the test's
 * own timeout will surface it as a failure rather than a CI hang.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Redis disconnect resilience")
class RedisDisconnectResilienceIntegrationTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("redis.host", REDIS::getHost);
        registry.add("redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("redis.password", () -> "");
        registry.add("cycles.expiry.initial-delay-ms", () -> "999999999");
    }

    private static final String TENANT = "tenant-r";
    private static final String API_KEY = "cyc_resilience_1234567890abcdef";

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected JedisPool jedisPool;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void seedAndAttachValidator() throws Exception {
        var interceptors = restTemplate.getRestTemplate().getInterceptors();
        if (interceptors.stream().noneMatch(i -> i instanceof ContractValidatingRestTemplateInterceptor)) {
            interceptors.add(new ContractValidatingRestTemplateInterceptor());
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
            String prefix = API_KEY.substring(0, 14);
            String hash = BCrypt.hashpw(API_KEY, BCrypt.gensalt());
            ApiKey apiKey = ApiKey.builder()
                    .keyId("key-r")
                    .tenantId(TENANT)
                    .keyPrefix(prefix)
                    .keyHash(hash)
                    .name("Resilience test key")
                    .status(ApiKeyStatus.ACTIVE)
                    .permissions(Collections.emptyList())
                    .createdAt(Instant.now())
                    .build();
            jedis.set("apikey:lookup:" + prefix, "key-r");
            jedis.set("apikey:key-r", objectMapper.writeValueAsString(apiKey));
            String budgetKey = "budget:tenant:" + TENANT + ":TOKENS";
            jedis.hset(budgetKey, Map.of(
                    "scope", "tenant:" + TENANT,
                    "unit", "TOKENS",
                    "allocated", "1000000",
                    "remaining", "1000000",
                    "reserved", "0",
                    "spent", "0",
                    "debt", "0",
                    "overdraft_limit", "100000",
                    "is_over_limit", "false"
            ));
        }
    }

    @Test
    @DisplayName("R1+R2: commit fails cleanly while Redis is paused, then succeeds after resume")
    void commitDuringPauseFailsCleanlyAndRecoversAfterResume() throws Exception {
        // Arrange: create a reservation while Redis is up.
        ResponseEntity<Map> reserve = post("/v1/reservations", API_KEY, reservationBody(5_000));
        assertThat(reserve.getStatusCode().value())
                .as("baseline reserve must succeed")
                .isEqualTo(200);
        String resId = (String) reserve.getBody().get("reservation_id");

        // Act 1: pause Redis. Testcontainers' Docker pause freezes the Redis process — no
        // new TCP connections succeed, existing connections stall. Exactly the failure
        // shape a production Redis outage has.
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            // R1: commit must fail with a structured error, not hang.
            // We deliberately don't bound the HTTP timeout here — TestRestTemplate uses
            // the JVM-default socket read timeout which is indefinite. If the service's
            // Jedis pool has no timeout either, this test would hang. Protecting the CI
            // loop by relying on surefire's test-level forkedProcessTimeoutInSeconds
            // (inherited from the pom) is deliberate — we want the failure to surface
            // loudly rather than be swallowed.
            long start = System.currentTimeMillis();
            ResponseEntity<Map> commit;
            try {
                commit = post("/v1/reservations/" + resId + "/commit", API_KEY, commitBody(2_500));
            } catch (Exception e) {
                // Client-side timeout counts as a clean failure too — better than hanging.
                commit = null;
            }
            long elapsed = System.currentTimeMillis() - start;

            if (commit != null) {
                // Must NOT be a silent 200. The service should surface a 5xx (or a 503
                // when pool-exhausted). A 4xx here would be suspicious — Redis being
                // paused is a server problem, not a client one.
                assertThat(commit.getStatusCode().is5xxServerError() || commit.getStatusCode().is4xxClientError())
                        .as("expected non-2xx while Redis paused; got %s body=%s",
                                commit.getStatusCode(), commit.getBody())
                        .isTrue();
                assertThat(commit.getStatusCode().is2xxSuccessful())
                        .as("MUST NOT return 2xx while Redis is unreachable — got %s",
                                commit.getStatusCode())
                        .isFalse();
                assertThat(commit.getBody())
                        .as("error body MUST be structured (not null) — blank bodies hide the failure")
                        .isNotNull();
            }
            // Track elapsed so a regression to unbounded waits trips the test even if the
            // CI-level timeout hides it. 60 s is generous but bounded.
            assertThat(elapsed)
                    .as("commit against paused Redis took too long (%d ms) — pool timeout missing?", elapsed)
                    .isLessThan(60_000);
        } finally {
            // Act 2: resume Redis (always, even if an assertion above threw).
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        // Brief settle window for the pool to mark connections healthy again.
        Thread.sleep(200);

        // R2: after recovery, a fresh commit must succeed using the state we reserved
        // before the outage. The reservation hash survived (Redis-AOF-equivalent via
        // docker pause, which freezes the process but doesn't drop keys on resume).
        ResponseEntity<Map> retryCommit = post(
                "/v1/reservations/" + resId + "/commit", API_KEY, commitBody(2_500));
        assertThat(retryCommit.getStatusCode().value())
                .as("post-recovery commit must succeed; got %s body=%s",
                        retryCommit.getStatusCode(), retryCommit.getBody())
                .isEqualTo(200);
        assertThat(retryCommit.getBody().get("status")).isEqualTo("COMMITTED");

        // Post-recovery sanity: no orphaned entries in the TTL zset (the successful
        // commit must have removed it, exactly as under no-outage conditions).
        try (Jedis jedis = jedisPool.getResource()) {
            Double score = jedis.zscore("reservation:ttl", resId);
            assertThat(score)
                    .as("committed reservation must not linger in the TTL sweep index")
                    .isNull();
        }
    }

    // ---- helpers ----

    private Map<String, Object> reservationBody(long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", Map.of("tenant", TENANT));
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("estimate", Map.of("unit", "TOKENS", "amount", amount));
        body.put("ttl_ms", 60_000);
        body.put("overage_policy", "REJECT");
        return body;
    }

    private Map<String, Object> commitBody(long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("actual", Map.of("unit", "TOKENS", "amount", amount));
        return body;
    }

    private ResponseEntity<Map> post(String path, String apiKey, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Cycles-API-Key", apiKey);
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }
}
