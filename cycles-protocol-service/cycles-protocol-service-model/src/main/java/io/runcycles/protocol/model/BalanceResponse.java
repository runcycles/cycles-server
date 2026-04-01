package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.25 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class BalanceResponse {
    @NotNull @Valid @JsonProperty("balances") private List<Balance> balances;
    @JsonProperty("has_more") private Boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
