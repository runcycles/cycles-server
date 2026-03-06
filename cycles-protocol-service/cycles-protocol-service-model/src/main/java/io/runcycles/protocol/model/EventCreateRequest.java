package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class EventCreateRequest {
    @NotNull @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("actual") private Amount actual;
    @JsonProperty("overage_policy") private Enums.CommitOveragePolicy overagePolicy;
}
