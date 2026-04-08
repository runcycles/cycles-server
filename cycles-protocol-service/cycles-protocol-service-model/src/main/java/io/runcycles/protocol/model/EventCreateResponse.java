package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;
import java.util.Map;

/** Cycles Protocol v0.1.25 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventCreateResponse {
    @NotNull @JsonProperty("status") private Enums.EventStatus status;
    @NotNull @JsonProperty("event_id") private String eventId;
    @Valid @JsonProperty("charged") private Amount charged;
    @Valid @JsonProperty("balances") private List<Balance> balances;
    /** Internal: per-scope debt incurred during this event for event emission. Not serialized. */
    @JsonIgnore private Map<String, Long> scopeDebtIncurred;
    /** Internal: per-scope pre-mutation remaining for transition detection. Not serialized. */
    @JsonIgnore private Map<String, Long> preRemaining;
    /** Internal: per-scope pre-mutation is_over_limit for transition detection. Not serialized. */
    @JsonIgnore private Map<String, Boolean> preIsOverLimit;
}
