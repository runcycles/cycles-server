package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.EvidenceQueueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class EvidenceEmitterTest {

    private final EvidenceQueueRepository repository = mock(EvidenceQueueRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceEmitter emitter = new EvidenceEmitter();

    @BeforeEach
    void wire() throws Exception {
        setField("repository", repository);
        setField("objectMapper", mapper);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = EvidenceEmitter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(emitter, value);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        emitter.destroy();
    }

    @Test
    void emitsRecordWithArtifactTypeTraceAndRequestResponsePayload() throws Exception {
        emitter.emit("reserve", 1810000000100L, "trace-abc",
                Map.of("idempotency_key", "k1"), Map.of("decision", "ALLOW"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository, timeout(2000)).push(captor.capture());

        JsonNode rec = mapper.readTree(captor.getValue());
        assertThat(rec.get("artifact_type").asText()).isEqualTo("reserve");
        assertThat(rec.get("issued_at_ms").asLong()).isEqualTo(1810000000100L);
        assertThat(rec.get("trace_id").asText()).isEqualTo("trace-abc");
        assertThat(rec.path("payload").path("request").path("idempotency_key").asText()).isEqualTo("k1");
        assertThat(rec.path("payload").path("response").path("decision").asText()).isEqualTo("ALLOW");
    }

    @Test
    void omitsTraceIdWhenBlank() throws Exception {
        emitter.emit("commit", 123L, "  ", Map.of("a", 1), Map.of("b", 2));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository, timeout(2000)).push(captor.capture());
        assertThat(mapper.readTree(captor.getValue()).has("trace_id")).isFalse();
    }
}
