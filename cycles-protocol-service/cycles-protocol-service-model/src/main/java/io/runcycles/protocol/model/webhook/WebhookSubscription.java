package io.runcycles.protocol.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.protocol.model.event.EventCategory;
import io.runcycles.protocol.model.event.EventType;
import lombok.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookSubscription {
    @JsonProperty("subscription_id") private String subscriptionId;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("name") private String name;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("description") private String description;
    @JsonProperty("url") private String url;
    @JsonProperty("event_types") private List<EventType> eventTypes;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("event_categories") private List<EventCategory> eventCategories;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("scope_filter") private String scopeFilter;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("thresholds") private WebhookThresholdConfig thresholds;
    @JsonIgnore private String signingSecret;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("headers") private Map<String, String> headers;
    @JsonProperty("status") private WebhookStatus status;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("retry_policy") private WebhookRetryPolicy retryPolicy;
    @JsonProperty("disable_after_failures") @Builder.Default private Integer disableAfterFailures = 10;
    @JsonProperty("consecutive_failures") private Integer consecutiveFailures;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("updated_at") private Instant updatedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("last_triggered_at") private Instant lastTriggeredAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("last_success_at") private Instant lastSuccessAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("last_failure_at") private Instant lastFailureAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("metadata") private Map<String, Object> metadata;
}
