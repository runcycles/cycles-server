package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.24 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventCreateResponse {
    @NotNull @JsonProperty("status") private Enums.EventStatus status;
    @NotNull @JsonProperty("event_id") private String eventId;
    @Valid @JsonProperty("charged") private Amount charged;
    @Valid @JsonProperty("balances") private List<Balance> balances;
}
