package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class ReservationCreateRequest {
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("estimate") private Amount estimate;
    @Positive @JsonProperty("ttl_ms") private Long ttlMs;
    @JsonProperty("grace_ms") private Long graceMs;
    @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @JsonProperty("affected_scopes") private List<String> affectedScopes;
}
