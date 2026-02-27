package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class ReleaseRequest {
    @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
}
