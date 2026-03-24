package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.24 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationSummary {
    @NotNull @JsonProperty("reservation_id") private String reservationId;
    @NotNull @JsonProperty("status") private Enums.ReservationStatus status;
    @JsonProperty("idempotency_key") private String idempotencyKey;
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("reserved") private Amount reserved;
    @NotNull @Min(0) @JsonProperty("created_at_ms") private Long createdAtMs;
    @NotNull @Min(0) @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @NotNull @JsonProperty("scope_path") private String scopePath;
    @NotNull @JsonProperty("affected_scopes") private List<String> affectedScopes;
}
