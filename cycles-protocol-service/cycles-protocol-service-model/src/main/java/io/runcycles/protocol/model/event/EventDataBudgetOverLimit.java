package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDataBudgetOverLimit {

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("debt")
    private Long debt;

    @JsonProperty("overdraft_limit")
    private Long overdraftLimit;

    @JsonProperty("is_over_limit")
    private Boolean isOverLimit;

    @JsonProperty("debt_utilization")
    private Double debtUtilization;
}
