package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;
import java.util.Map;

/** Cycles Protocol v0.1.25 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReservationSummary {
    @NotNull @JsonProperty("reservation_id") private String reservationId;
    @NotNull @JsonProperty("status") private Enums.ReservationStatus status;
    @JsonProperty("idempotency_key") private String idempotencyKey;
    @NotNull @Valid @JsonProperty("subject") private Subject subject;
    @NotNull @Valid @JsonProperty("action") private Action action;
    @NotNull @Valid @JsonProperty("reserved") private Amount reserved;
    @NotNull @Min(0) @JsonProperty("created_at_ms") private Long createdAtMs;
    @NotNull @Min(0) @JsonProperty("expires_at_ms") private Long expiresAtMs;
    /** Wall-clock time at which the reservation reached a terminal state.
     * Populated on COMMITTED and RELEASED rows only; absent on ACTIVE and
     * EXPIRED. Mirrors the same field on ReservationDetail.
     * Spec: cycles-protocol-v0.yaml revision 2026-05-22. */
    @Min(0) @JsonProperty("finalized_at_ms") private Long finalizedAtMs;
    @NotNull @JsonProperty("scope_path") private String scopePath;
    @NotNull @JsonProperty("affected_scopes") private List<String> affectedScopes;
    /** The amount charged at COMMIT. Present on COMMITTED rows only; NON_NULL
     * strips it on ACTIVE / EXPIRED / RELEASED rows. Projected UNCONDITIONALLY
     * on listReservations rows (no include opt-in needed), on the same footing
     * as finalized_at_ms. Spec: cycles-protocol-v0.yaml revision 2026-06-19
     * (cycles-server#201). */
    @Valid @JsonProperty("committed") private Amount committed;
    /** RESERVE-time metadata. Always present (when populated) on the single-row
     * ReservationDetail; on listReservations it is OMITTED BY DEFAULT and
     * projected only when the caller opts in via include=metadata — the map is
     * arbitrary-size and may carry application PII, so it is not sprayed across
     * every list row. Spec: cycles-protocol-v0.yaml revision 2026-06-19. */
    @JsonProperty("metadata") private Map<String, Object> metadata;
    /** COMMIT-time metadata. Same include-gated list semantics as metadata
     * (include=committed_metadata). Spec: cycles-protocol-v0.yaml revision
     * 2026-06-19 (cycles-server#197 read-path, #201 list projection). */
    @JsonProperty("committed_metadata") private Map<String, Object> committedMetadata;
    /** CyclesEvidence references for this reservation's reserve / commit /
     * release operations, keyed by artifact type — lets a consumer resolve the
     * signed envelope(s) via getEvidence without having captured the
     * evidence_id off the original response. Always present (when recorded) on
     * the single-row ReservationDetail; on listReservations it is OMITTED BY
     * DEFAULT and projected only when the caller opts in via include=evidence.
     * Spec: cycles-protocol-v0.yaml revision 2026-06-22 (v0.1.25.9). */
    @Valid @JsonProperty("evidence") private ReservationEvidence evidence;
}
