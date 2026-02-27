package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class Balance {
    @JsonProperty("scope") private String scope;
    @JsonProperty("scope_path") private String scopePath;
    @JsonProperty("unit") private Enums.UnitEnum unit;
    @JsonProperty("allocated") private Long allocated;
    @JsonProperty("remaining") private Long remaining;
    @JsonProperty("reserved") private Long reserved;
    @JsonProperty("spent") private Long spent;
    @JsonProperty("debt") private Long debt;
    @JsonProperty("overdraft_limit") private Long overdraftLimit;
    @JsonProperty("is_over_limit") private Boolean isOverLimit;
}
