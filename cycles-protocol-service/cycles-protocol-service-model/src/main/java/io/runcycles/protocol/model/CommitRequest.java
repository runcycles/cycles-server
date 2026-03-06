package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class CommitRequest {
    @NotNull @Valid @JsonProperty("actual") private Amount actual;
    @NotNull @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @JsonProperty("metrics") private StandardMetrics metrics;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
