package io.runcycles.protocol.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookThresholdConfig {
    @JsonProperty("budget_utilization") private List<Double> budgetUtilization;
    @JsonProperty("burn_rate_multiplier") @Builder.Default private Double burnRateMultiplier = 3.0;
    @JsonProperty("burn_rate_window_seconds") @Builder.Default private Integer burnRateWindowSeconds = 300;
    @JsonProperty("denial_rate_threshold") @Builder.Default private Double denialRateThreshold = 0.10;
    @JsonProperty("expiry_rate_threshold") @Builder.Default private Double expiryRateThreshold = 0.05;
    @JsonProperty("auth_failure_rate_threshold") @Builder.Default private Double authFailureRateThreshold = 0.10;
    @JsonProperty("rate_window_seconds") @Builder.Default private Integer rateWindowSeconds = 300;
}
