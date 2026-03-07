package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReleaseResponse {
    @JsonProperty("status") private String status;
    @JsonProperty("released") private Amount released;
    @JsonProperty("balances") private List<Balance> balances;
}
