package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.*;
import java.util.List;

/** Cycles Protocol v0.1.23 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReservationListResponse {
    // Spec: GET /v1/reservations returns ReservationSummary (not ReservationDetail).
    // contentAs ensures subtype fields (committed, finalized_at_ms, metadata) are never serialized here.
    @JsonProperty("reservations") @JsonSerialize(contentAs = ReservationSummary.class)
    private List<ReservationSummary> reservations;
    @JsonProperty("has_more") private Boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
