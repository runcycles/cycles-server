package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationExtendResponse {
    @JsonProperty("status") private String status;
    @JsonProperty("expires_at_ms") private Long expiresAtMs;
}
