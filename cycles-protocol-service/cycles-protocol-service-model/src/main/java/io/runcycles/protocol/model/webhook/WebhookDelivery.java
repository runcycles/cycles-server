package io.runcycles.protocol.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.protocol.model.event.EventType;
import lombok.*;
import java.time.Instant;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookDelivery {
    @JsonProperty("delivery_id") private String deliveryId;
    @JsonProperty("subscription_id") private String subscriptionId;
    @JsonProperty("event_id") private String eventId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("event_type") private EventType eventType;
    @JsonProperty("status") private DeliveryStatus status;
    @JsonProperty("attempted_at") private Instant attemptedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("completed_at") private Instant completedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("attempts") private Integer attempts;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("response_status") private Integer responseStatus;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("response_time_ms") private Integer responseTimeMs;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("error_message") private String errorMessage;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_retry_at") private Instant nextRetryAt;
}
