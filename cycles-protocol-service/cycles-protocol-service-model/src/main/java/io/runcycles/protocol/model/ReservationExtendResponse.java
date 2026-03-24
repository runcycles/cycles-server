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
public class ReservationExtendResponse {
    @NotNull @JsonProperty("status") private Enums.ExtendStatus status;
    @NotNull @Min(0) @JsonProperty("expires_at_ms") private Long expiresAtMs;
    @Valid @JsonProperty("balances") private List<Balance> balances;
}
