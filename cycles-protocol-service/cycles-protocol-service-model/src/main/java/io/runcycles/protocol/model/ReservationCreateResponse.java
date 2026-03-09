package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationCreateResponse {
    @NotNull @JsonProperty("decision") private Enums.DecisionEnum decision;
    @JsonProperty("reservation_id") private String reservationId;
    @NotNull @JsonProperty("affected_scopes") private List<String> affectedScopes;
    @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @JsonProperty("scope_path") private String scopePath;
    @Valid @JsonProperty("reserved") private Amount reserved;
    @Valid @JsonProperty("caps") private Caps caps;
    @Size(max = 128) @JsonProperty("reason_code") private String reasonCode;
    @Min(0) @JsonProperty("retry_after_ms") private Integer retryAfterMs;
    @Valid @JsonProperty("balances") private List<Balance> balances;
}
