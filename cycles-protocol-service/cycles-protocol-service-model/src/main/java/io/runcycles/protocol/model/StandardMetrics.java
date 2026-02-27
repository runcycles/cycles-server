package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StandardMetrics {
    @JsonProperty("tokens") private Long tokens;
    @JsonProperty("steps") private Long steps;
    @JsonProperty("cache_hit") private Boolean cacheHit;
}
