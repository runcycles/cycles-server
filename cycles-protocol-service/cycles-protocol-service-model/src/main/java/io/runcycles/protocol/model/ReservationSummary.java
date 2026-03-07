package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationSummary {
    @NotNull @JsonProperty("reservation_id") private String reservationId;
    @NotNull @JsonProperty("status") private Enums.ReservationStatus status;
    @JsonProperty("idempotency_key") private String idempotencyKey;
    @NotNull @JsonProperty("subject") private Subject subject;
    @NotNull @JsonProperty("action") private Action action;
    @NotNull @JsonProperty("reserved") private Amount reserved;
    @NotNull @JsonProperty("created_at_ms") private Long createdAtMs;
    @NotNull @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @NotNull @JsonProperty("scope_path") private String scopePath;
    @NotNull @JsonProperty("affected_scopes") private List<String> affectedScopes;
}
