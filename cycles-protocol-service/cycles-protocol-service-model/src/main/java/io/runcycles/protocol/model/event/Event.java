package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private EventType eventType;

    @JsonProperty("category")
    private EventCategory category;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("scope")
    private String scope;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("actor")
    private Actor actor;

    @JsonProperty("source")
    private String source;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("data")
    private Map<String, Object> data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("correlation_id")
    private String correlationId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("request_id")
    private String requestId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("metadata")
    private Map<String, Object> metadata;
}
