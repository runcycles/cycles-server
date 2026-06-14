package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.EvidenceQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EvidenceEmitterTest {

    private static final String SERVER_ID = "https://cycles.example.com/v1";
    private static final String SIGNER_DID = "ec52b49b81eb29ef6f62947cade245c715bf943b7ef2a5f2789288574466fc43";

    private final EvidenceQueueRepository repository = mock(EvidenceQueueRepository.class);
    private final CyclesMetrics metrics = mock(CyclesMetrics.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceEmitter emitter = new EvidenceEmitter();

    @BeforeEach
    void wire() throws Exception {
        setField("repository", repository);
        setField("objectMapper", mapper);
        setField("metrics", metrics);
        setField("evidenceIdComputer", new EvidenceIdComputer(mapper));
        // default: identity unconfigured (blank) — individual tests configure it
        setField("serverId", "");
        setField("signerDid", "");
    }

    private void configureIdentity() throws Exception {
        setField("serverId", SERVER_ID);
        setField("signerDid", SIGNER_DID);
    }

    @Test
    void enqueuesSynchronouslyWithArtifactTypeTraceAndPayloadBody() throws Exception {
        emitter.emit("reserve", 1810000000100L, "trace-abc",
                Map.of("request", Map.of("idempotency_key", "k1"),
                        "response", Map.of("decision", "ALLOW")));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).push(captor.capture()); // synchronous — no timeout needed

        JsonNode rec = mapper.readTree(captor.getValue());
        assertThat(rec.get("artifact_type").asText()).isEqualTo("reserve");
        assertThat(rec.get("issued_at_ms").asLong()).isEqualTo(1810000000100L);
        assertThat(rec.get("trace_id").asText()).isEqualTo("trace-abc");
        assertThat(rec.path("payload").path("request").path("idempotency_key").asText()).isEqualTo("k1");
        assertThat(rec.path("payload").path("response").path("decision").asText()).isEqualTo("ALLOW");
    }

    @Test
    void stripsNullValuedPropertiesFromTheEvidencePayload() throws Exception {
        // The evidence mirrors are additionalProperties:false with non-nullable typed
        // fields, so an unset request field serialized as null (e.g. ttl_ms / metadata)
        // would make the envelope fail mirror validation. Null-strip it.
        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("idempotency_key", "k1");
        request.put("ttl_ms", null);
        request.put("overage_policy", null);
        request.put("metadata", null);
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("request", request);
        payload.put("response", Map.of("decision", "ALLOW"));

        emitter.emit("reserve", 100L, "trace-x", payload);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).push(captor.capture());
        JsonNode req = mapper.readTree(captor.getValue()).path("payload").path("request");
        assertThat(req.path("idempotency_key").asText()).isEqualTo("k1");
        assertThat(req.has("ttl_ms")).isFalse();
        assertThat(req.has("overage_policy")).isFalse();
        assertThat(req.has("metadata")).isFalse();
        assertThat(captor.getValue()).doesNotContain(":null");
    }

    @Test
    void evidenceIdIsComputedOverTheNullStrippedPayload() throws Exception {
        // The id stamped on the record and the bytes the worker recomputes over must
        // derive from the SAME null-stripped payload, or the worker dead-letters on drift.
        configureIdentity();
        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("idempotency_key", "k1");
        request.put("ttl_ms", null);
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("request", request);
        payload.put("response", Map.of("decision", "ALLOW"));

        emitter.emit("reserve", 100L, "trace-x", payload);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).push(captor.capture());
        JsonNode rec = mapper.readTree(captor.getValue());
        // recompute the id over the stored (null-stripped) payload body the worker reads
        String recomputed = new EvidenceIdComputer(mapper).compute(
                "reserve", SERVER_ID, SIGNER_DID, 100L, "trace-x", rec.path("payload"));
        assertThat(rec.path("evidence_id").asText()).hasSize(64).isEqualTo(recomputed);
    }

    @Test
    void omitsTraceIdWhenBlank() throws Exception {
        emitter.emit("commit", 123L, "  ", Map.of("request", Map.of("a", 1), "response", Map.of("b", 2)));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).push(captor.capture());
        assertThat(mapper.readTree(captor.getValue()).has("trace_id")).isFalse();
    }

    @Test
    void returnsNullAndOmitsEvidenceIdWhenIdentityUnconfigured() throws Exception {
        // default wire() leaves server-id/signer-did blank
        EvidenceEmitter.EvidenceRef ref = emitter.emit("reserve", 1L, null,
                Map.of("request", Map.of(), "response", Map.of("decision", "ALLOW")));

        assertThat(ref).isNull();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).push(captor.capture());
        assertThat(mapper.readTree(captor.getValue()).has("evidence_id")).isFalse();
    }

    @Test
    void computesEvidenceIdAndReturnsRefWhenConfigured() throws Exception {
        configureIdentity();

        EvidenceEmitter.EvidenceRef ref = emitter.emit("reserve", 1810000000100L, "trace-abc",
                Map.of("response", Map.of("decision", "ALLOW")));

        assertThat(ref).isNotNull();
        assertThat(ref.evidenceId()).matches("^[0-9a-f]{64}$");
        // server_id already includes /v1, so the url adds only /evidence/{id}
        assertThat(ref.cyclesEvidenceUrl())
                .isEqualTo(SERVER_ID + "/evidence/" + ref.evidenceId())
                .doesNotContain("/v1/v1/");

        // the same evidence_id is stamped on the queued record for the worker to cross-check
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).push(captor.capture());
        assertThat(mapper.readTree(captor.getValue()).get("evidence_id").asText())
                .isEqualTo(ref.evidenceId());
    }

    @Test
    void evidenceUrlJoinsCleanlyWhenServerIdHasTrailingSlash() throws Exception {
        setField("serverId", "https://cycles.example.com/v1/");
        setField("signerDid", SIGNER_DID);

        EvidenceEmitter.EvidenceRef ref = emitter.emit("reserve", 1L, null,
                Map.of("response", Map.of("decision", "ALLOW")));

        // trailing slash trimmed, no double slash, and /v1 not re-added
        assertThat(ref.cyclesEvidenceUrl())
                .isEqualTo("https://cycles.example.com/v1/evidence/" + ref.evidenceId())
                .doesNotContain("//evidence")
                .doesNotContain("/v1/v1/");
    }

    @Test
    void failsOpenAndMetersWhenPushThrows() {
        doThrow(new RuntimeException("redis down")).when(repository).push(anyString());

        // a push failure must NOT propagate (the ledger write already committed)
        assertThatCode(() -> assertThat(emitter.emit("reserve", 1L, null,
                Map.of("request", Map.of(), "response", Map.of()))).isNull())
                .doesNotThrowAnyException();

        verify(metrics).recordEvidenceEmitFailed("reserve");
    }

    private void setField(String name, Object value) throws Exception {
        Field f = EvidenceEmitter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(emitter, value);
    }
}
