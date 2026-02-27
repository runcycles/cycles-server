package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
public class Subject {
    @NotBlank @Size(max = 128) @JsonProperty("tenant") private String tenant;
    @Size(max = 128) @JsonProperty("workspace") private String workspace;
    @Size(max = 128) @JsonProperty("app") private String app;
    @Size(max = 128) @JsonProperty("workflow") private String workflow;
    @Size(max = 128) @JsonProperty("agent") private String agent;
    @Size(max = 128) @JsonProperty("toolGroup") private String toolGroup;
    @JsonProperty("dimensions") private Map<String, String> dimensions;
}
