package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class Balance {
    @NotNull @JsonProperty("scope") private String scope;
    @NotNull @JsonProperty("scope_path") private String scopePath;
    @NotNull @Valid @JsonProperty("remaining") private SignedAmount remaining;
    @Valid @JsonProperty("reserved") private Amount reserved;
    @Valid @JsonProperty("spent") private Amount spent;
    @Valid @JsonProperty("allocated") private Amount allocated;
    @Valid @JsonProperty("debt") private Amount debt;
    @Valid @JsonProperty("overdraft_limit") private Amount overdraftLimit;
    @JsonProperty("is_over_limit") private Boolean isOverLimit;
}
