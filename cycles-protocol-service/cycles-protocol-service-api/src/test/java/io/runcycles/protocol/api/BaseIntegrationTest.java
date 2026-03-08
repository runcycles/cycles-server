package io.runcycles.protocol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {

    @BeforeAll
    static void checkDocker() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — skipping integration tests");
    }

    protected static final String TENANT_A = "tenant-a";
    protected static final String TENANT_B = "tenant-b";
    protected static final String API_KEY_SECRET_A = "cyc_testa1234567890abcdef";
    protected static final String API_KEY_SECRET_B = "cyc_testb1234567890abcdef";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("redis.host", REDIS::getHost);
        registry.add("redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("redis.password", () -> "");
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

    private String extractPrefix(String keySecret) {
        int idx = keySecret.indexOf('_');
        return idx > 0 ? keySecret.substring(0, Math.min(idx + 6, keySecret.length()))
                       : keySecret.substring(0, Math.min(10, keySecret.length()));
    }

    protected void seedBudget(Jedis jedis, String tenant, String unit, long allocated) {
        String key = "budget:tenant:" + tenant + ":" + unit;
        jedis.hset(key, Map.of(
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
}
