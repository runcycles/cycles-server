package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.25 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationExtendResponse {
    @NotNull @JsonProperty("status") private Enums.ExtendStatus status;
    @NotNull @Min(0) @JsonProperty("expires_at_ms") private Long expiresAtMs;
    /** Remaining reservation lifetime (ms) at response evaluation, same clock
     *  snapshot as expires_at_ms. Recomputed FRESH on idempotent replays (a
     *  heartbeat retrying a lost extend schedules from this value). */
    @Min(0) @JsonProperty("remaining_ttl_ms") private Long remainingTtlMs;
    @Valid @JsonProperty("balances") private List<Balance> balances;
}
