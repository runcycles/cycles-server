package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class ReservationExtendRequest {
    @Positive @JsonProperty("additional_ttl_ms") private Long additionalTtlMs;
    @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
}
