package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;
import java.util.Map;

/** Cycles Protocol v0.1.25.1 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationCreateResponse {
    @NotNull @JsonProperty("decision") private Enums.DecisionEnum decision;
    @JsonProperty("reservation_id") private String reservationId;
    @NotNull @JsonProperty("affected_scopes") private List<String> affectedScopes;
    @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @JsonProperty("scope_path") private String scopePath;
    @Valid @JsonProperty("reserved") private Amount reserved;
    @Valid @JsonProperty("caps") private Caps caps;
    @JsonProperty("reason_code") private Enums.ReasonCode reasonCode;
    @Min(0) @JsonProperty("retry_after_ms") private Integer retryAfterMs;
    @Valid @JsonProperty("balances") private List<Balance> balances;
    @Valid @JsonProperty("cycles_evidence") private CyclesEvidenceRef cyclesEvidence;
    /** Internal: per-scope pre-mutation remaining for transition detection. Not serialized. */
    @JsonIgnore private Map<String, Long> preRemaining;
    /** Internal: per-scope pre-mutation is_over_limit for transition detection. Not serialized. */
    @JsonIgnore private Map<String, Boolean> preIsOverLimit;
    /** Internal: true when this response is an idempotent replay of a prior reserve.
     *  Drives idempotent CyclesEvidence handling (replay the original ref; never
     *  recompute or re-emit). Not serialized. */
    @JsonIgnore private boolean idempotentReplay;
    /** Internal: the CyclesEvidence id/url persisted at first create, replayed on
     *  idempotency hits so the same idempotency_key returns the same evidence. Not serialized. */
    @JsonIgnore private String storedEvidenceId;
    @JsonIgnore private String storedEvidenceUrl;
}
