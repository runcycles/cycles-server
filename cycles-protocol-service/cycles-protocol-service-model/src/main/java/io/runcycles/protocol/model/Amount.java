package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;

/** Cycles Protocol v0.1.25 */
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class Amount {
    @NotNull @JsonProperty("unit") private Enums.UnitEnum unit;
    @NotNull @Min(0) @JsonProperty("amount") private Long amount;
}
