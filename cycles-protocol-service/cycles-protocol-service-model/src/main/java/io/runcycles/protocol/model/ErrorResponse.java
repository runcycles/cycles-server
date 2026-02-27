package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    @JsonProperty("error") private Enums.ErrorCode error;
    @JsonProperty("message") private String message;
    @JsonProperty("request_id") private String requestId;
    @JsonProperty("details") private Map<String, Object> details;
}
