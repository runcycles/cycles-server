package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.24 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class EventCreateRequest {
    @NotNull @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("actual") private Amount actual;
    @JsonProperty("overage_policy") private Enums.CommitOveragePolicy overagePolicy;
    @Valid @JsonProperty("metrics") private StandardMetrics metrics;
    @Min(0) @JsonProperty("client_time_ms") private Long clientTimeMs;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
