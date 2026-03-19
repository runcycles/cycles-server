package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationCreateRequest {
    @NotNull @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("estimate") private Amount estimate;
    @Min(1000) @Max(86400000) @JsonProperty("ttl_ms") private Long ttlMs = 60000L;
    @Min(0) @Max(60000) @JsonProperty("grace_period_ms") private Long gracePeriodMs = 5000L;
    @JsonProperty("overage_policy") private Enums.CommitOveragePolicy overagePolicy;
    @JsonProperty("dry_run") private Boolean dryRun = false;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
