package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.25 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class DecisionResponse {
    @NotNull @JsonProperty("decision") private Enums.DecisionEnum decision;
    @Size(max = 128) @JsonProperty("reason_code") private String reasonCode;
    @Valid @JsonProperty("caps") private Caps caps;
    @Min(0) @JsonProperty("retry_after_ms") private Integer retryAfterMs;
    @JsonProperty("affected_scopes") private List<String> affectedScopes;
}
