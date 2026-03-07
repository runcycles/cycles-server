package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationSummary {
    @JsonProperty("reservation_id") private String reservationId;
    @JsonProperty("status") private Enums.ReservationStatus status;
    @JsonProperty("idempotency_key") private String idempotencyKey;
    @JsonProperty("subject") private Subject subject;
    @JsonProperty("action") private Action action;
    @JsonProperty("reserved") private Amount reserved;
    @JsonProperty("created_at_ms") private Long createdAtMs;
    @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @JsonProperty("scope_path") private String scopePath;
    @JsonProperty("affected_scopes") private List<String> affectedScopes;
}
