package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationCreateResponse {
    @JsonProperty("decision") private String decision;
    @JsonProperty("reservation_id") private String reservationId;
    @JsonProperty("affected_scopes") private List<String> affectedScopes;
    @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @JsonProperty("scope_path") private String scopePath;
    @JsonProperty("reserved") private Amount reserved;
    @JsonProperty("caps") private Caps caps;
    @JsonProperty("reason_code") private String reasonCode;
    @JsonProperty("retry_after_ms") private Integer retryAfterMs;
}
