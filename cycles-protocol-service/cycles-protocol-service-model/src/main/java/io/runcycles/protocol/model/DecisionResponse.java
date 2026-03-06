package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class DecisionResponse {
    @JsonProperty("decision") private String decision;
    @JsonProperty("reason_code") private String reasonCode;
    @JsonProperty("caps") private Caps caps;
    @JsonProperty("retry_after_ms") private Integer retryAfterMs;
}
