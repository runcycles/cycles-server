package io.runcycles.protocol.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Field-projection tokens for the {@code listReservations} {@code include}
 * query parameter (cycles-protocol-v0.yaml revision 2026-06-19).
 *
 * <p>Projection-only: {@code include} selects which optional heavy fields are
 * serialized onto each {@link ReservationSummary} list row. It never affects
 * which rows match, their ordering, pagination, or cursor / filter-hash
 * binding. Unrecognized and empty tokens are ignored without error
 * (forward/backward compatible). {@code committed} is NOT gated here — it is
 * always projected on list rows.
 */
public enum ReservationInclude {
    METADATA("metadata"),
    COMMITTED_METADATA("committed_metadata");

    private final String wire;

    ReservationInclude(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * Parse a comma-separated {@code include} value into the set of recognized
     * tokens. Surrounding whitespace is trimmed; empty tokens (e.g. a trailing
     * comma, or {@code include=}) and unrecognized tokens are ignored. Null or
     * blank input yields an empty set.
     */
    public static Set<ReservationInclude> parseCsv(String raw) {
        EnumSet<ReservationInclude> out = EnumSet.noneOf(ReservationInclude.class);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            for (ReservationInclude inc : values()) {
                if (inc.wire.equals(t)) {
                    out.add(inc);
                    break;
                }
            }
        }
        return out;
    }
}
