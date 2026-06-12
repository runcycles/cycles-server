package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-parity tests for {@link EvidenceIdComputer}.
 *
 * <p>These reuse the SAME golden {@code cycles-evidence-fixtures} the event-tier
 * {@code CyclesEvidenceCanonicalizer} verifies against (and which the APS
 * verifier was generated with). Computing the {@code evidence_id} from the
 * operational facts here and asserting it equals each fixture's embedded
 * {@code evidence_id} proves cycles-server's synchronous computation is
 * byte-for-byte identical to the worker that signs + stores the envelope — i.e.
 * the id returned on the reserve response will resolve to a real envelope.
 */
class EvidenceIdComputerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceIdComputer computer = new EvidenceIdComputer(mapper);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "02-reserve-allow", "04-reserve-allow-with-caps", "08-reserve-allow-no-trace-id"
    })
    void matchesGoldenFixtureEvidenceId(String fixture) throws Exception {
        ObjectNode env = loadFixture(fixture);
        String artifactType = env.get("artifact_type").asText();
        String serverId = env.get("server_id").asText();
        String signerDid = env.get("signer_did").asText();
        long issuedAtMs = env.get("issued_at_ms").asLong();
        String traceId = env.hasNonNull("trace_id") ? env.get("trace_id").asText() : null;
        // payload body is nested under payload.<artifact_type>; the computer
        // re-nests it, so pass the inner body (mirrors what the worker receives).
        JsonNode payloadBody = env.get("payload").get(artifactType);

        String computed = computer.compute(artifactType, serverId, signerDid, issuedAtMs, traceId, payloadBody);

        assertThat(computed)
                .as("evidence_id byte-equality with golden fixture %s", fixture)
                .isEqualTo(env.get("evidence_id").asText());
    }

    @Test
    void omittingBlankTraceIdChangesTheId() {
        // trace_id present vs omitted must canonicalize differently (the field is
        // either in the JCS bytes or not) — guards the blank-trace omission rule.
        ObjectNode body = mapper.createObjectNode();
        body.set("reserve", mapper.createObjectNode().put("decision", "ALLOW"));
        String withTrace = computer.compute("reserve", "https://s/v1", "ab".repeat(32),
                1L, "trace-xyz", body.get("reserve"));
        String blankTrace = computer.compute("reserve", "https://s/v1", "ab".repeat(32),
                1L, "  ", body.get("reserve"));
        assertThat(withTrace).isNotEqualTo(blankTrace);
        // a blank trace_id behaves identically to an omitted one
        String noTrace = computer.compute("reserve", "https://s/v1", "ab".repeat(32),
                1L, null, body.get("reserve"));
        assertThat(blankTrace).isEqualTo(noTrace);
    }

    @Test
    void producesLowercaseSha256Hex() {
        String id = computer.compute("reserve", "https://s/v1", "ab".repeat(32),
                1L, null, mapper.createObjectNode().put("decision", "ALLOW"));
        assertThat(id).matches("^[0-9a-f]{64}$");
    }

    private ObjectNode loadFixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/cycles-evidence-fixtures/" + name + ".json")) {
            assertThat(in).as("fixture %s present", name).isNotNull();
            return (ObjectNode) mapper.readTree(in);
        }
    }
}
