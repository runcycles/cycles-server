package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class Balance {
    @NotNull @JsonProperty("scope") private String scope;
    @NotNull @JsonProperty("scope_path") private String scopePath;
    @NotNull @JsonProperty("remaining") private SignedAmount remaining;
    @JsonProperty("reserved") private Amount reserved;
    @JsonProperty("spent") private Amount spent;
    @JsonProperty("allocated") private Amount allocated;
    @JsonProperty("debt") private Amount debt;
    @JsonProperty("overdraft_limit") private Amount overdraftLimit;
    @JsonProperty("is_over_limit") private Boolean isOverLimit;
}
