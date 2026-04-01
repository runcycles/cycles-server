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
public class EventDataCommitOverage {

    @JsonProperty("reservation_id")
    private String reservationId;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("estimated_amount")
    private Long estimatedAmount;

    @JsonProperty("actual_amount")
    private Long actualAmount;

    @JsonProperty("overage")
    private Long overage;

    @JsonProperty("overage_policy")
    private String overagePolicy;

    @JsonProperty("debt_incurred")
    private Long debtIncurred;
}
