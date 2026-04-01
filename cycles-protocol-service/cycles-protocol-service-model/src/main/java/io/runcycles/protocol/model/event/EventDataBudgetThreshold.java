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
public class EventDataBudgetThreshold {

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("threshold")
    private Double threshold;

    @JsonProperty("utilization")
    private Double utilization;

    @JsonProperty("allocated")
    private Long allocated;

    @JsonProperty("remaining")
    private Long remaining;

    @JsonProperty("spent")
    private Long spent;

    @JsonProperty("reserved")
    private Long reserved;

    @JsonProperty("direction")
    private String direction;
}
