package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class CommitResponse {
    @NotNull @JsonProperty("status") private String status;
    @NotNull @JsonProperty("charged") private Amount charged;
    @JsonProperty("released") private Amount released;
    @JsonProperty("balances") private List<Balance> balances;
}
