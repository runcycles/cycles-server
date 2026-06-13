package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.25 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReleaseResponse {
    @NotNull @JsonProperty("status") private Enums.ReleaseStatus status;
    @NotNull @Valid @JsonProperty("released") private Amount released;
    @Valid @JsonProperty("balances") private List<Balance> balances;
    @Valid @JsonProperty("cycles_evidence") private CyclesEvidenceRef cyclesEvidence;
    /** Internal: true when this is an idempotent replay (return the cached body verbatim;
     *  never re-emit/re-stamp evidence). Not serialized. */
    @JsonIgnore private boolean idempotentReplay;
}
