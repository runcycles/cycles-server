package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import lombok.*;

/**
 * Map of artifact type to the CyclesEvidence reference emitted for that
 * operation on a reservation (cycles-protocol-v0.yaml revision 2026-06-22,
 * v0.1.25.9). Lets a consumer jump from a reservation straight to its signed
 * envelope(s) via {@code getEvidence} without having captured the
 * {@code evidence_id} off the original reserve / commit / release response.
 *
 * <p>A reservation has at most a {@code reserve} entry plus one terminal entry
 * ({@code commit} XOR {@code release}). TRANSPORT METADATA, NOT ATTESTED (see
 * {@link CyclesEvidenceRef}) — each entry is recorded after its artifact's
 * {@code evidence_id} was computed. {@code NON_NULL} strips absent artifacts;
 * the whole object is absent when the reservation has no recorded evidence
 * (emission disabled, or the reservation predates evidence support).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationEvidence {
    @Valid @JsonProperty("reserve") private CyclesEvidenceRef reserve;
    @Valid @JsonProperty("commit") private CyclesEvidenceRef commit;
    @Valid @JsonProperty("release") private CyclesEvidenceRef release;

    /** True when no artifact ref is set — used to avoid attaching an empty map. */
    @JsonIgnore
    public boolean isEmpty() {
        return reserve == null && commit == null && release == null;
    }
}
