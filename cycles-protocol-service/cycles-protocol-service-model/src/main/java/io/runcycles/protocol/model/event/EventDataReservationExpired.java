package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDataReservationExpired {

    @JsonProperty("reservation_id")
    private String reservationId;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("estimated_amount")
    private Long estimatedAmount;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("expired_at")
    private Instant expiredAt;

    @JsonProperty("ttl_ms")
    private Integer ttlMs;

    @JsonProperty("extensions_used")
    private Integer extensionsUsed;
}
