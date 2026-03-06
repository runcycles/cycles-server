package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardMetrics {
    @JsonProperty("tokens_input") private Long tokensInput;
    @JsonProperty("tokens_output") private Long tokensOutput;
    @JsonProperty("latency_ms") private Long latencyMs;
    @JsonProperty("model_version") private String modelVersion;
    @JsonProperty("custom") private Map<String, Object> custom;
}
