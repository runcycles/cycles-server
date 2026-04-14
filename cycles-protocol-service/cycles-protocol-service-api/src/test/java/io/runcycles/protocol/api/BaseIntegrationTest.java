package io.runcycles.protocol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.api.contract.ContractValidatingRestTemplateInterceptor;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import org.junit.jupiter.api.BeforeEach;
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

import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    protected static final String TENANT_A = "tenant-a";
    protected static final String TENANT_B = "tenant-b";
    protected static final String API_KEY_SECRET_A = "cyc_testa1234567890abcdef";
    protected static final String API_KEY_SECRET_B = "cyc_testb1234567890abcdef";

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

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JedisPool jedisPool;

    protected final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void attachContractValidator() {
        // Integration tests go through real controller/service/Redis/Lua paths,
        // so wiring the interceptor here validates every response against the
        // pinned spec with zero per-test changes. Idempotent — adding the same
        // interceptor twice across tests has no ill effect in Spring's internal
        // list, but we guard to keep the list small.
        var interceptors = restTemplate.getRestTemplate().getInterceptors();
        boolean present = interceptors.stream()
                .anyMatch(i -> i instanceof ContractValidatingRestTemplateInterceptor);
        if (!present) {
            interceptors.add(new ContractValidatingRestTemplateInterceptor());
        }
    }

    @BeforeEach
    void seedTestData() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
            seedApiKey(jedis, "key-a", TENANT_A, API_KEY_SECRET_A);
            seedApiKey(jedis, "key-b", TENANT_B, API_KEY_SECRET_B);
            seedBudget(jedis, TENANT_A, "TOKENS", 1_000_000);
            seedBudget(jedis, TENANT_B, "TOKENS", 500_000);
        }
    }

    private void seedApiKey(Jedis jedis, String keyId, String tenantId, String secret) throws Exception {
        String hash = BCrypt.hashpw(secret, BCrypt.gensalt());
        String prefix = extractPrefix(secret);

        ApiKey apiKey = ApiKey.builder()
                .keyId(keyId)
                .tenantId(tenantId)
                .keyPrefix(prefix)
                .keyHash(hash)
                .name("Test key for " + tenantId)
                .status(ApiKeyStatus.ACTIVE)
                .permissions(Collections.emptyList())
                .createdAt(Instant.now())
                .build();

        jedis.set("apikey:lookup:" + prefix, keyId);
        jedis.set("apikey:" + keyId, objectMapper.writeValueAsString(apiKey));
    }

    private static final int PREFIX_LENGTH = 14;
    private String extractPrefix(String keySecret) {
        return keySecret.substring(0, Math.min(PREFIX_LENGTH, keySecret.length()));
    }

    protected void seedBudget(Jedis jedis, String tenant, String unit, long allocated) {
        String key = "budget:tenant:" + tenant + ":" + unit;
        jedis.hset(key, Map.of(
                "scope", "tenant:" + tenant,
                "unit", unit,
                "allocated", String.valueOf(allocated),
                "remaining", String.valueOf(allocated),
                "reserved", "0",
                "spent", "0",
                "debt", "0",
                "overdraft_limit", String.valueOf(allocated / 10),
                "is_over_limit", "false"
        ));
    }

    // ---- HTTP helpers ----

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected HttpHeaders headersForTenant(String apiKeySecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Cycles-API-Key", apiKeySecret);
        return headers;
    }

    protected ResponseEntity<Map> post(String path, String apiKey, Map<String, Object> body) {
        return restTemplate.exchange(
                baseUrl() + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headersForTenant(apiKey)),
                Map.class
        );
    }

    protected ResponseEntity<Map> get(String path, String apiKey) {
        return restTemplate.exchange(
                baseUrl() + path,
                HttpMethod.GET,
                new HttpEntity<>(headersForTenant(apiKey)),
                Map.class
        );
    }

    // ---- Request builders ----

    protected Map<String, Object> reservationBody(String tenant, long amount) {
        return reservationBody(tenant, amount, "TOKENS");
    }

    protected Map<String, Object> reservationBody(String tenant, long amount, String unit) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", Map.of("tenant", tenant));
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("estimate", Map.of("unit", unit, "amount", amount));
        body.put("ttl_ms", 60000);
        body.put("overage_policy", "REJECT");
        return body;
    }

    protected Map<String, Object> commitBody(long amount) {
        return commitBody(amount, "TOKENS");
    }

    protected Map<String, Object> commitBody(long amount, String unit) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("actual", Map.of("unit", unit, "amount", amount));
        return body;
    }

    protected Map<String, Object> releaseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("reason", "test-release");
        return body;
    }

    protected Map<String, Object> extendBody(long extendByMs) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("extend_by_ms", extendByMs);
        return body;
    }

    protected Map<String, Object> decisionBody(String tenant, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", Map.of("tenant", tenant));
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("estimate", Map.of("unit", "TOKENS", "amount", amount));
        return body;
    }

    protected Map<String, Object> eventBody(String tenant, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", Map.of("tenant", tenant));
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("actual", Map.of("unit", "TOKENS", "amount", amount));
        return body;
    }

    protected String createReservationAndGetId(String tenant, String apiKey, long amount) {
        ResponseEntity<Map> response = post("/v1/reservations", apiKey, reservationBody(tenant, amount));
        assert response.getStatusCode().is2xxSuccessful() : "Failed to create reservation: " + response.getBody();
        return (String) response.getBody().get("reservation_id");
    }

    /**
     * Seed a tenant record in Redis with configurable defaults.
     */
    protected void seedTenant(Jedis jedis, String tenantId, String overagePolicy,
                               Long defaultTtlMs, Long maxTtlMs, Integer maxExtensions) throws Exception {
        Map<String, Object> tenant = new HashMap<>();
        tenant.put("tenant_id", tenantId);
        tenant.put("name", tenantId);
        tenant.put("status", "ACTIVE");
        tenant.put("created_at", Instant.now().toString());
        if (overagePolicy != null) tenant.put("default_commit_overage_policy", overagePolicy);
        if (defaultTtlMs != null) tenant.put("default_reservation_ttl_ms", defaultTtlMs);
        if (maxTtlMs != null) tenant.put("max_reservation_ttl_ms", maxTtlMs);
        if (maxExtensions != null) tenant.put("max_reservation_extensions", maxExtensions);
        jedis.set("tenant:" + tenantId, objectMapper.writeValueAsString(tenant));
    }

    /**
     * Seed a budget at an arbitrary scope path (e.g. "tenant:tenant-a/workspace:default/agent:my-agent").
     */
    protected void seedScopeBudget(Jedis jedis, String scopePath, String unit, long allocated, long overdraftLimit) {
        String key = "budget:" + scopePath + ":" + unit;
        jedis.hset(key, Map.of(
                "scope", scopePath,
                "unit", unit,
                "allocated", String.valueOf(allocated),
                "remaining", String.valueOf(allocated),
                "reserved", "0",
                "spent", "0",
                "debt", "0",
                "overdraft_limit", String.valueOf(overdraftLimit),
                "is_over_limit", "false"
        ));
    }

    /**
     * Build a reservation request body with a multi-level subject.
     */
    protected Map<String, Object> reservationBodyWithSubject(Map<String, String> subject, long amount, String unit) {
        Map<String, Object> body = new HashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("subject", subject);
        body.put("action", Map.of("kind", "llm.completion", "name", "test-model"));
        body.put("estimate", Map.of("unit", unit, "amount", amount));
        body.put("ttl_ms", 60000);
        body.put("overage_policy", "REJECT");
        return body;
    }

    /**
     * Force a reservation's expires_at to a past timestamp (for expiration testing).
     */
    protected void expireReservationInRedis(String reservationId, long pastExpiresAt) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "reservation:res_" + reservationId;
            jedis.hset(key, "expires_at", String.valueOf(pastExpiresAt));
            jedis.hset(key, "grace_ms", "0");
            // Update TTL sorted set so the sweep can find it
            jedis.zadd("reservation:ttl", pastExpiresAt, reservationId);
        }
    }

    // ---- Redis inspection helpers (read-only; for invariant assertions) ----

    /**
     * Fetch a reservation's full hash from Redis. Returns empty map if not found.
     * Pass the raw reservation id (e.g. "res_abc123") or just the suffix; both accepted.
     */
    protected Map<String, String> getReservationStateFromRedis(String reservationId) {
        String key = reservationId.startsWith("res_")
                ? "reservation:" + reservationId
                : "reservation:res_" + reservationId;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll(key);
        }
    }

    /**
     * Fetch a budget's full hash from Redis for a given scope path and unit.
     */
    protected Map<String, String> getBudgetFromRedis(String scopePath, String unit) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll("budget:" + scopePath + ":" + unit);
        }
    }

    /**
     * SCAN-based enumeration of all reservation:res_* keys. Non-blocking alternative to KEYS.
     */
    protected Set<String> scanReservationKeys() {
        Set<String> out = new HashSet<>();
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("reservation:res_*").count(200);
            String cursor = "0";
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                out.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
        }
        return out;
    }

    /**
     * Seed a budget with a custom overdraft_limit (overrides the default allocated/10).
     * Used by tests that need to force REJECT behavior (overdraft_limit=0).
     */
    protected void seedBudgetWithOverdraftLimit(Jedis jedis, String tenant, String unit,
                                                 long allocated, long overdraftLimit) {
        String key = "budget:tenant:" + tenant + ":" + unit;
        jedis.hset(key, Map.of(
                "scope", "tenant:" + tenant,
                "unit", unit,
                "allocated", String.valueOf(allocated),
                "remaining", String.valueOf(allocated),
                "reserved", "0",
                "spent", "0",
                "debt", "0",
                "overdraft_limit", String.valueOf(overdraftLimit),
                "is_over_limit", "false"
        ));
    }

    /**
     * Seed a budget with a specific status (e.g. FROZEN, CLOSED).
     */
    protected void seedBudgetWithStatus(Jedis jedis, String tenant, String unit, long allocated, String status) {
        String key = "budget:tenant:" + tenant + ":" + unit;
        jedis.hset(key, Map.of(
                "scope", "tenant:" + tenant,
                "unit", unit,
                "allocated", String.valueOf(allocated),
                "remaining", String.valueOf(allocated),
                "reserved", "0",
                "spent", "0",
                "debt", "0",
                "overdraft_limit", String.valueOf(allocated / 10),
                "is_over_limit", "false",
                "status", status
        ));
    }
}
