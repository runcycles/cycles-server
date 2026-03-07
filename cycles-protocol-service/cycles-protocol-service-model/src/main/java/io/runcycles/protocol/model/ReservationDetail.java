package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @EqualsAndHashCode(callSuper = true) @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationDetail extends ReservationSummary {
    @JsonProperty("committed") private Amount committed;
    @JsonProperty("finalized_at_ms") private Long finalizedAtMs;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
