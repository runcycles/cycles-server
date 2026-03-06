package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class DecisionRequest {
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("estimate") private Amount estimate;
    @Positive @JsonProperty("ttl_ms") private Long ttlMs;
    @JsonProperty("overage_policy") private Enums.CommitOveragePolicy overagePolicy;
    @JsonProperty("affected_scopes") private List<String> affectedScopes;
}
