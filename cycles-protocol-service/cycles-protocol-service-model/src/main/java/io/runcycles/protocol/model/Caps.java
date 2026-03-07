package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class Caps {
    @Min(0) @JsonProperty("max_tokens") private Long maxTokens;
    @Min(0) @JsonProperty("max_steps_remaining") private Long maxStepsRemaining;
    @JsonProperty("tool_allowlist") private List<String> toolAllowlist;
    @JsonProperty("tool_denylist") private List<String> toolDenylist;
    @Min(0) @JsonProperty("cooldown_ms") private Long cooldownMs;
}
