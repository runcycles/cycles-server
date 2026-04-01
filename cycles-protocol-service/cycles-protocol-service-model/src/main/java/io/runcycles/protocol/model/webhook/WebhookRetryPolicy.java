package io.runcycles.protocol.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookRetryPolicy {
    @JsonProperty("max_retries") @Builder.Default private Integer maxRetries = 5;
    @JsonProperty("initial_delay_ms") @Builder.Default private Integer initialDelayMs = 1000;
    @JsonProperty("backoff_multiplier") @Builder.Default private Double backoffMultiplier = 2.0;
    @JsonProperty("max_delay_ms") @Builder.Default private Integer maxDelayMs = 60000;
}
