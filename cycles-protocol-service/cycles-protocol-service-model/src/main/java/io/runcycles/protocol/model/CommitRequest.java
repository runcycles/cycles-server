package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.25 */
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class CommitRequest {
    @NotNull @Valid @JsonProperty("actual") private Amount actual;
    @NotNull @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @Valid @JsonProperty("metrics") private StandardMetrics metrics;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
