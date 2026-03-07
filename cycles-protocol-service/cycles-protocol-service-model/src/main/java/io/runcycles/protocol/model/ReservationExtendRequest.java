package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class ReservationExtendRequest {
    @NotNull @Min(1) @Max(86400000) @JsonProperty("extend_by_ms") private Long extendByMs;
    @NotNull @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
