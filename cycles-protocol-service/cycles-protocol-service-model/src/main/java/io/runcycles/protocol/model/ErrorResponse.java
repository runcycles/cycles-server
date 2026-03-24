package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.24 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ErrorResponse {
    @NotNull @JsonProperty("error") private Enums.ErrorCode error;
    @NotNull @JsonProperty("message") private String message;
    @NotNull @JsonProperty("request_id") private String requestId;
    @JsonProperty("details") private Map<String, Object> details;
}
