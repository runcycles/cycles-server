package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class EventCreateResponse {
    @JsonProperty("event_id") private String eventId;
    @JsonProperty("status") private String status;
    @JsonProperty("charged") private Amount charged;
    @JsonProperty("scope_path") private String scopePath;
    @JsonProperty("affected_scopes") private List<String> affectedScopes;
}
