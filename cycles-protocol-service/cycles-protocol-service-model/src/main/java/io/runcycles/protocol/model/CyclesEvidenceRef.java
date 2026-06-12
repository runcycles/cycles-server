package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;

/**
 * Reference to the CyclesEvidence envelope emitted for an operation (Cycles
 * Protocol v0.1.25.1 — {@code CyclesEvidenceRef}).
 *
 * <p>The {@code evidence_id} is the sha256 content hash of the JCS-canonical
 * envelope, computed synchronously at decision time; the envelope itself is
 * Ed25519-signed and stored asynchronously. A caller binds its own signed
 * receipt to this evidence by recording {@code evidence_id} and resolving the
 * envelope via {@code getEvidence} at {@code cycles_evidence_url}.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class CyclesEvidenceRef {
    @NotNull
    @Pattern(regexp = "^[0-9a-f]{64}$")
    @JsonProperty("evidence_id")
    private String evidenceId;

    @NotNull
    @JsonProperty("cycles_evidence_url")
    private String cyclesEvidenceUrl;
}
