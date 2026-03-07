package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReservationListResponse {
    @NotNull @JsonProperty("reservations") private List<ReservationSummary> reservations;
    @JsonProperty("has_more") private Boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
