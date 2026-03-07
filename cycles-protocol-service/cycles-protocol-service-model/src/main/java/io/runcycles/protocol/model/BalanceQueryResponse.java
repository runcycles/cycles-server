package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BalanceQueryResponse {
    @NotNull @JsonProperty("balances") private List<Balance> balances;
    @JsonProperty("has_more") private Boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
