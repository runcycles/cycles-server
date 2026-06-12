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

    private final EvidenceQueueRepository repository = mock(EvidenceQueueRepository.class);
    private final CyclesMetrics metrics = mock(CyclesMetrics.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceEmitter emitter = new EvidenceEmitter();

    @BeforeEach
    void wire() throws Exception {
        setField("repository", repository);
        setField("objectMapper", mapper);
        setField("metrics", metrics);
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
    void omitsTraceIdWhenBlank() throws Exception {
        emitter.emit("commit", 123L, "  ", Map.of("request", Map.of("a", 1), "response", Map.of("b", 2)));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).push(captor.capture());
        assertThat(mapper.readTree(captor.getValue()).has("trace_id")).isFalse();
    }

    @Test
    void failsOpenAndMetersWhenPushThrows() {
        doThrow(new RuntimeException("redis down")).when(repository).push(anyString());

        // a push failure must NOT propagate (the ledger write already committed)
        assertThatCode(() -> emitter.emit("reserve", 1L, null,
                Map.of("request", Map.of(), "response", Map.of()))).doesNotThrowAnyException();

        verify(metrics).recordEvidenceEmitFailed("reserve");
    }

    private void setField(String name, Object value) throws Exception {
        Field f = EvidenceEmitter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(emitter, value);
    }
}
