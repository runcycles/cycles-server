package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.25 */
@Data @EqualsAndHashCode(callSuper = true) @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationDetail extends ReservationSummary {
    @Valid @JsonProperty("committed") private Amount committed;
    @JsonProperty("finalized_at_ms") private Long finalizedAtMs;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
