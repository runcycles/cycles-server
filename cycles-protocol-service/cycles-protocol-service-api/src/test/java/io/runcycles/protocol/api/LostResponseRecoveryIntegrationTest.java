package io.runcycles.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.runcycles.protocol.data.service.EventEmitterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic lost-response recovery matrix.
 *
 * <p>The first successful response is deliberately treated as unavailable to
 * the client. Repeating the exact request must return the canonical original
 * body without applying the ledger mutation, evidence enqueue, or runtime
 * event side effects a second time. This models a connection loss after Redis
 * committed but before the caller received the HTTP response without relying
 * on timing-sensitive packet interruption.</p>
 */
@DisplayName("Lost-response idempotency recovery")
@TestPropertySource(properties = {
        "cycles.evidence.server-id=https://lost-response.test/v1",
        "cycles.evidence.signing.signer-did=did:key:lost-response-test",
        "cycles.events.emit.threads=1"
})
class LostResponseRecoveryIntegrationTest extends BaseIntegrationTest {

    private static final String BUDGET_KEY = "budget:tenant:" + TENANT_A + ":TOKENS";

    @Autowired
    private EventEmitterService eventEmitter;

    @Test
    @DisplayName("reserve retry returns the original body and reserves exactly once")
    void reserveRecoversAfterSuccessfulResponseIsLost() throws Exception {
        Map<String, Object> request = reservationBody(TENANT_A, 1_000);

        ResponseEntity<String> lost = postRaw("/v1/reservations", request);
        assertThat(lost.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(lost);
        String reservationId = body.path("reservation_id").asText();

        ResponseEntity<String> replay = postRaw("/v1/reservations", request);

        assertCanonicalReplay(lost, replay, 200);
        assertThat(body.path("cycles_evidence").path("evidence_id").asText()).isNotBlank();
        assertBudget(999_000, 1_000, 0);
        assertThat(scanReservationKeys()).containsExactly("reservation:res_" + reservationId);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.zscore("reservation:ttl", reservationId)).isNotNull();
            assertThat(jedis.llen("evidence:pending")).isEqualTo(1);
        }
        assertMismatch(postRaw("/v1/reservations", withAmount(request, 1_001)));
        assertBudget(999_000, 1_000, 0);
        assertThat(evidenceQueueLength()).isEqualTo(1);
    }

    @Test
    @DisplayName("commit retry returns the original body and settles exactly once")
    void commitRecoversAfterSuccessfulResponseIsLost() throws Exception {
        String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
        long evidenceBefore = evidenceQueueLength();
        Map<String, Object> request = commitBody(800);
        String path = "/v1/reservations/" + reservationId + "/commit";

        ResponseEntity<String> lost = postRaw(path, request);
        ResponseEntity<String> replay = postRaw(path, request);

        assertCanonicalReplay(lost, replay, 200);
        assertThat(json(lost).path("cycles_evidence").path("evidence_id").asText()).isNotBlank();
        assertBudget(999_200, 0, 800);
        assertThat(getReservationStateFromRedis(reservationId))
                .containsEntry("state", "COMMITTED")
                .containsEntry("charged_amount", "800");
        assertFinalizedReservation(reservationId);
        assertThat(scanReservationKeys()).containsExactly("reservation:res_" + reservationId);
        assertThat(evidenceQueueLength()).isEqualTo(evidenceBefore + 1);
        assertMismatch(postRaw(path, withAmount(request, 801)));
        assertBudget(999_200, 0, 800);
        assertThat(evidenceQueueLength()).isEqualTo(evidenceBefore + 1);
    }

    @Test
    @DisplayName("release retry returns the original body and refunds exactly once")
    void releaseRecoversAfterSuccessfulResponseIsLost() throws Exception {
        String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);
        long evidenceBefore = evidenceQueueLength();
        Map<String, Object> request = releaseBody();
        String path = "/v1/reservations/" + reservationId + "/release";

        ResponseEntity<String> lost = postRaw(path, request);
        ResponseEntity<String> replay = postRaw(path, request);

        assertCanonicalReplay(lost, replay, 200);
        assertThat(json(lost).path("cycles_evidence").path("evidence_id").asText()).isNotBlank();
        assertBudget(1_000_000, 0, 0);
        assertThat(getReservationStateFromRedis(reservationId))
                .containsEntry("state", "RELEASED");
        assertFinalizedReservation(reservationId);
        assertThat(scanReservationKeys()).containsExactly("reservation:res_" + reservationId);
        assertThat(evidenceQueueLength()).isEqualTo(evidenceBefore + 1);
        Map<String, Object> changed = new HashMap<>(request);
        changed.put("reason", "changed-after-lost-response");
        assertMismatch(postRaw(path, changed));
        assertBudget(1_000_000, 0, 0);
        assertThat(evidenceQueueLength()).isEqualTo(evidenceBefore + 1);
    }

    @Test
    @DisplayName("event retry returns the original body and debits exactly once")
    void eventRecoversAfterSuccessfulResponseIsLost() throws Exception {
        Map<String, Object> request = eventBody(TENANT_A, 1_000_000);

        ResponseEntity<String> lost = postRaw("/v1/events", request);
        ResponseEntity<String> replay = postRaw("/v1/events", request);

        assertCanonicalReplay(lost, replay, 201);
        String eventId = json(lost).path("event_id").asText();
        assertBudget(0, 0, 1_000_000);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.type("event:evt_" + eventId)).isEqualTo("hash");
            assertThat(jedis.hget("event:evt_" + eventId, "event_response_json")).isNotBlank();
            assertThat(countKeysOfType(jedis, "event:evt_*", "hash")).isEqualTo(1);
        }
        assertRuntimeEventCountAfterDrain(1);
        assertThat(evidenceQueueLength()).isZero();
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.pttl("event:evt_" + eventId)).isPositive();
            assertThat(countKeysOfType(jedis, "event:evt_*", "hash")).isEqualTo(1);
        }
        assertMismatch(postRaw("/v1/events", withAmount(request, 999_999)));
        assertBudget(0, 0, 1_000_000);
        assertRuntimeEventCountAfterDrain(1);
        assertThat(evidenceQueueLength()).isZero();
    }

    @Test
    @DisplayName("dry-run retry returns the original denial without mutating or re-emitting")
    void dryRunRecoversAfterSuccessfulResponseIsLost() throws Exception {
        Map<String, Object> request = reservationBody(TENANT_A, 1_000_001);
        request.put("dry_run", true);

        ResponseEntity<String> lost = postRaw("/v1/reservations", request);
        ResponseEntity<String> replay = postRaw("/v1/reservations", request);

        assertCanonicalReplay(lost, replay, 200);
        JsonNode body = json(lost);
        assertThat(body.path("decision").asText()).isEqualTo("DENY");
        assertThat(body.path("cycles_evidence").path("evidence_id").asText()).isNotBlank();
        assertBudget(1_000_000, 0, 0);
        assertThat(scanReservationKeys()).isEmpty();
        assertThat(evidenceQueueLength()).isEqualTo(1);
        assertRuntimeEventCountAfterDrain(1);
        assertMismatch(postRaw("/v1/reservations", withAmount(request, 1_000_002)));
        assertBudget(1_000_000, 0, 0);
        assertThat(evidenceQueueLength()).isEqualTo(1);
        assertRuntimeEventCountAfterDrain(1);
    }

    @Test
    @DisplayName("decide retry returns the original denial without mutating or re-emitting")
    void decideRecoversAfterSuccessfulResponseIsLost() throws Exception {
        Map<String, Object> request = decisionBody(TENANT_A, 1_000_001);

        ResponseEntity<String> lost = postRaw("/v1/decide", request);
        ResponseEntity<String> replay = postRaw("/v1/decide", request);

        assertCanonicalReplay(lost, replay, 200);
        JsonNode body = json(lost);
        assertThat(body.path("decision").asText()).isEqualTo("DENY");
        assertThat(body.path("cycles_evidence").path("evidence_id").asText()).isNotBlank();
        assertBudget(1_000_000, 0, 0);
        assertThat(scanReservationKeys()).isEmpty();
        assertThat(evidenceQueueLength()).isEqualTo(1);
        assertRuntimeEventCountAfterDrain(1);
        assertMismatch(postRaw("/v1/decide", withAmount(request, 1_000_002)));
        assertBudget(1_000_000, 0, 0);
        assertThat(evidenceQueueLength()).isEqualTo(1);
        assertRuntimeEventCountAfterDrain(1);
    }

    private ResponseEntity<String> postRaw(String path, Map<String, Object> body) {
        return restTemplate.exchange(
                baseUrl() + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headersForTenant(API_KEY_SECRET_A)),
                String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertCanonicalReplay(ResponseEntity<String> original,
                                       ResponseEntity<String> replay,
                                       int expectedStatus) {
        assertThat(original.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(replay.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(replay.getBody()).isEqualTo(original.getBody());
    }

    private void assertBudget(long remaining, long reserved, long spent) {
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.hmget(BUDGET_KEY, "remaining", "reserved", "spent", "debt"))
                    .containsExactly(String.valueOf(remaining), String.valueOf(reserved),
                            String.valueOf(spent), "0");
        }
    }

    private void assertFinalizedReservation(String reservationId) {
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.zscore("reservation:ttl", reservationId)).isNull();
            assertThat(jedis.pttl("reservation:res_" + reservationId)).isPositive();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withAmount(Map<String, Object> request, long amount) {
        Map<String, Object> changed = new HashMap<>(request);
        String field = request.containsKey("actual") ? "actual" : "estimate";
        Map<String, Object> originalAmount = (Map<String, Object>) request.get(field);
        changed.put(field, Map.of("unit", originalAmount.get("unit"), "amount", amount));
        return changed;
    }

    private void assertMismatch(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.getBody()).path("error").asText())
                .isEqualTo("IDEMPOTENCY_MISMATCH");
    }

    private long countKeysOfType(Jedis jedis, String pattern, String type) {
        long count = 0;
        String cursor = "0";
        ScanParams params = new ScanParams().match(pattern).count(100);
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            count += result.getResult().stream()
                    .filter(key -> type.equals(jedis.type(key)))
                    .count();
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
        return count;
    }

    private long evidenceQueueLength() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.llen("evidence:pending");
        }
    }

    private void assertRuntimeEventCountAfterDrain(long expected) throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor)
                ReflectionTestUtils.getField(eventEmitter, "emitExecutor");
        assertThat(executor).isNotNull();
        // The test context pins one emitter thread. A FIFO barrier submitted after
        // the HTTP call makes every earlier event durable without timing sleeps.
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.zcard("events:" + TENANT_A)).isEqualTo(expected);
        }
    }
}
