package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDataReservationDenied {

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("reason_code")
    private String reasonCode;

    @JsonProperty("requested_amount")
    private Long requestedAmount;

    @JsonProperty("remaining")
    private Long remaining;

    @JsonProperty("action")
    private Map<String, Object> action;

    @JsonProperty("subject")
    private Map<String, Object> subject;
}
