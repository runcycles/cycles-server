package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class StandardMetrics {
    @Min(0) @JsonProperty("tokens_input") private Integer tokensInput;
    @Min(0) @JsonProperty("tokens_output") private Integer tokensOutput;
    @Min(0) @JsonProperty("latency_ms") private Integer latencyMs;
    @Size(max = 128) @JsonProperty("model_version") private String modelVersion;
    @JsonProperty("custom") private Map<String, Object> custom;
}
