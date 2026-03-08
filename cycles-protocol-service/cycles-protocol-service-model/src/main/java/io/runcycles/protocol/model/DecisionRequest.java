package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class DecisionRequest {
    @NotNull @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("estimate") private Amount estimate;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
