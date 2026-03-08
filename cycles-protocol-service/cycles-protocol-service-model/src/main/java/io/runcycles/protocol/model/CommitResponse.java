package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class CommitResponse {
    @NotNull @JsonProperty("status") private Enums.CommitStatus status;
    @NotNull @Valid @JsonProperty("charged") private Amount charged;
    @Valid @JsonProperty("released") private Amount released;
    @JsonProperty("balances") private List<Balance> balances;
}
