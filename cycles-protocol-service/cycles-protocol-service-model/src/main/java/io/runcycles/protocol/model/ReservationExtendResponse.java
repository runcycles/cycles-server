package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationExtendResponse {
    @NotNull @JsonProperty("status") private String status;
    @NotNull @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @JsonProperty("balances") private List<Balance> balances;
}
