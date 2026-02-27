package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationCreateResponse {
    @JsonProperty("reservation_id") private String reservationId;
    @JsonProperty("state") private Enums.ReservationState state;
    @JsonProperty("subject") private Subject subject;
    @JsonProperty("action") private Action action;
    @JsonProperty("estimate") private Amount estimate;
    @JsonProperty("affected_scopes") private java.util.List<String> affectedScopes;
    @JsonProperty("expires_at") private Instant expiresAt;
    @JsonProperty("soft_landing") private Caps softLanding;
}
