package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;

/** Cycles Protocol v0.1.23 - Like Amount but allows negative values (e.g., remaining in overdraft state) */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class SignedAmount {
    @JsonProperty("unit") private Enums.UnitEnum unit;
    @JsonProperty("amount") private Long amount;
}
