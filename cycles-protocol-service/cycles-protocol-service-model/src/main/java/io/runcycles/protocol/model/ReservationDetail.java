package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;

/**
 * Cycles Protocol v0.1.25 — single-reservation detail
 * (GET /v1/reservations/{reservation_id}).
 *
 * <p>As of cycles-protocol-v0.yaml revision 2026-06-19 (cycles-server#201) the
 * {@code committed}, {@code metadata}, and {@code committed_metadata} fields
 * live on {@link ReservationSummary}, so the list and single-row projections
 * share one shape. ReservationDetail remains a distinct type for the
 * single-GET response, where those fields are always populated (when present
 * on the record); the list path opts into the metadata maps via the
 * {@code include} query parameter. */
@EqualsAndHashCode(callSuper = true) @NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationDetail extends ReservationSummary {
}
