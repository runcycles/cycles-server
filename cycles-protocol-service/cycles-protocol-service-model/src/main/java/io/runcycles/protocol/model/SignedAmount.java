package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** Cycles Protocol v0.1.23 - Like Amount but allows negative values (e.g., remaining in overdraft state) */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class SignedAmount {
    @NotNull @JsonProperty("unit") private Enums.UnitEnum unit;
    @NotNull @JsonProperty("amount") private Long amount;
}
