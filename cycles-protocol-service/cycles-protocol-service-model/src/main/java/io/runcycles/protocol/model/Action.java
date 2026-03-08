package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class Action {
    @NotBlank @Size(max = 64) @JsonProperty("kind") private String kind;
    @NotBlank @Size(max = 256) @JsonProperty("name") private String name;
    @Size(max = 10) @JsonProperty("tags") private List<@Size(max = 64) String> tags;
}
