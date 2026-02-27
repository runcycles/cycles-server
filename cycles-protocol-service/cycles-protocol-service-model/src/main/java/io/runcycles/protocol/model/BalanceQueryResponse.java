package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class BalanceQueryResponse {
    @JsonProperty("balances") private List<Balance> balances;
}
