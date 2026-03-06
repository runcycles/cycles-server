package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class CommitResponse {
    @JsonProperty("status") private String status;
    @JsonProperty("charged") private Amount charged;
    @JsonProperty("released") private Amount released;
}
