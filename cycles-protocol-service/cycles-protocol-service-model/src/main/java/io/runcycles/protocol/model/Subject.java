package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

/**
 * Cycles Protocol v0.1.23
 *
 * YAML spec: anyOf requires at least one of tenant/workspace/app/workflow/agent/toolset.
 * A Subject containing only dimensions is invalid (server MUST return 400 INVALID_REQUEST).
 */
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class Subject {
    @Size(max = 128) @JsonProperty("tenant") private String tenant;
    @Size(max = 128) @JsonProperty("workspace") private String workspace;
    @Size(max = 128) @JsonProperty("app") private String app;
    @Size(max = 128) @JsonProperty("workflow") private String workflow;
    @Size(max = 128) @JsonProperty("agent") private String agent;
    @Size(max = 128) @JsonProperty("toolset") private String toolset;
    @Size(max = 16) @JsonProperty("dimensions") private Map<String, @Size(max = 256) String> dimensions;

    /**
     * Validates that at least one standard field is set (spec: anyOf constraint).
     * Returns true if at least one of tenant/workspace/app/workflow/agent/toolset is non-blank.
     */
    @AssertTrue(message = "At least one standard field (tenant, workspace, app, workflow, agent, toolset) must be provided")
    @JsonIgnore
    public boolean isHasAtLeastOneStandardField() {
        return isNonBlank(tenant) || isNonBlank(workspace) || isNonBlank(app)
            || isNonBlank(workflow) || isNonBlank(agent) || isNonBlank(toolset);
    }

    public boolean hasAtLeastOneStandardField() {
        return isHasAtLeastOneStandardField();
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
