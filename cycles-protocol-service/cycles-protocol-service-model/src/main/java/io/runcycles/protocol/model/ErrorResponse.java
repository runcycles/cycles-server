package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.25 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ErrorResponse {
    @NotNull @JsonProperty("error") private Enums.ErrorCode error;
    @NotNull @JsonProperty("message") private String message;
    @NotNull @JsonProperty("request_id") private String requestId;
    @JsonProperty("trace_id") private String traceId;
    @JsonProperty("details") private Map<String, Object> details;
    /**
     * Reference to the CyclesEvidence envelope emitted for this error
     * (artifact_type {@code error}); per cycles-protocol v0.1.25.5. Present only
     * when the server emitted an {@code error} envelope for this response — the
     * budget/lifecycle denials (e.g. HTTP 409 {@code BUDGET_EXCEEDED}); absent
     * for pre-evaluation validation/auth failures and when emission is disabled.
     * TRANSPORT METADATA, NOT attested — stamped AFTER {@code evidence_id} is
     * computed over the pre-stamp response, so the envelope's
     * {@code payload.error.response} content hash is never self-referential.
     */
    @Valid @JsonProperty("cycles_evidence") private CyclesEvidenceRef cyclesEvidence;
}
