package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class CommitResponse {
    @JsonProperty("reservation_id") private String reservationId;
    @JsonProperty("state") private Enums.ReservationState state;
    @JsonProperty("actual") private Amount actual;
    @JsonProperty("charged") private Amount charged;
    @JsonProperty("debt_incurred") private Long debtIncurred;
    @JsonProperty("committed_at") private Instant committedAt;
}
