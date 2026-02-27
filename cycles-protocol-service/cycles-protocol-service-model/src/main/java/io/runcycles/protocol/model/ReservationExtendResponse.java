package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationExtendResponse {
    @JsonProperty("reservation_id") private String reservationId;
    @JsonProperty("new_expires_at") private Instant newExpiresAt;
    @JsonProperty("extended_at") private Instant extendedAt;
}
