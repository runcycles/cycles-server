package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class Amount {
    @NotNull @JsonProperty("unit") private Enums.UnitEnum unit;
    @NotNull @Min(0) @JsonProperty("amount") private Long amount;
}
