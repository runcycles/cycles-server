package io.runcycles.protocol.data.repository.support;

import io.runcycles.protocol.model.ReservationSummary;
import io.runcycles.protocol.model.Amount;
import io.runcycles.protocol.model.Enums;

import java.util.Comparator;

/**
 * Comparator factory for {@link ReservationSummary} matching the {@code sort_by} enum
 * in cycles-protocol-v0.yaml revision 2026-04-16.
 *
 * <p>Supported sort keys: {@code reservation_id}, {@code tenant}, {@code scope_path},
 * {@code status}, {@code reserved}, {@code created_at_ms}, {@code expires_at_ms}.
 *
 * <p>Every comparator appends a stable tiebreaker on {@code reservation_id} ASC so total
 * ordering is deterministic — the cursor slice depends on a strict total order.
 *
 * <p>Null-safe: extractors that can legitimately be null ({@code subject.tenant} on
 * malformed rows, {@code reserved.amount} on historical rows) sort nulls last under both
 * ASC and DESC to keep pagination stable.
 */
public final class ReservationComparators {
    private ReservationComparators() {}

    public static Comparator<ReservationSummary> of(String sortByWire, String sortDirWire) {
        Enums.ReservationSortBy sortBy = sortByWire == null
            ? Enums.ReservationSortBy.CREATED_AT_MS
            : Enums.ReservationSortBy.valueOf(sortByWire.toUpperCase());
        Enums.SortDirection sortDir = sortDirWire == null
            ? Enums.SortDirection.DESC
            : Enums.SortDirection.valueOf(sortDirWire.toUpperCase());

        Comparator<ReservationSummary> primary = primaryFor(sortBy);
        if (sortDir == Enums.SortDirection.DESC) {
            primary = primary.reversed();
        }
        return primary.thenComparing(
            ReservationSummary::getReservationId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Extract the sort-key value for a given row as the string form stored in the cursor's
     * {@code lsv} field. The comparator uses the typed value; the cursor stores the wire-safe
     * string so resume logic can reconstruct the slice boundary.
     */
    public static String extractSortValue(ReservationSummary r, String sortByWire) {
        Enums.ReservationSortBy sortBy = sortByWire == null
            ? Enums.ReservationSortBy.CREATED_AT_MS
            : Enums.ReservationSortBy.valueOf(sortByWire.toUpperCase());
        switch (sortBy) {
            case RESERVATION_ID: return nullSafe(r.getReservationId());
            case TENANT:         return nullSafe(r.getSubject() == null ? null : r.getSubject().getTenant());
            case SCOPE_PATH:     return nullSafe(r.getScopePath());
            case STATUS:         return r.getStatus() == null ? "" : r.getStatus().name();
            case RESERVED:       return r.getReserved() == null || r.getReserved().getAmount() == null
                                     ? "" : String.valueOf(r.getReserved().getAmount());
            case CREATED_AT_MS:  return r.getCreatedAtMs() == null ? "" : String.valueOf(r.getCreatedAtMs());
            case EXPIRES_AT_MS:  return r.getExpiresAtMs() == null ? "" : String.valueOf(r.getExpiresAtMs());
            default: throw new IllegalStateException("Unhandled sort_by: " + sortBy);
        }
    }

    private static Comparator<ReservationSummary> primaryFor(Enums.ReservationSortBy sortBy) {
        switch (sortBy) {
            case RESERVATION_ID:
                return Comparator.comparing(
                    ReservationSummary::getReservationId, Comparator.nullsLast(Comparator.naturalOrder()));
            case TENANT:
                return Comparator.comparing(
                    (ReservationSummary r) -> r.getSubject() == null ? null : r.getSubject().getTenant(),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case SCOPE_PATH:
                return Comparator.comparing(
                    ReservationSummary::getScopePath, Comparator.nullsLast(Comparator.naturalOrder()));
            case STATUS:
                return Comparator.comparing(
                    (ReservationSummary r) -> r.getStatus() == null ? null : r.getStatus().name(),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case RESERVED:
                return Comparator.comparing(
                    (ReservationSummary r) -> {
                        Amount a = r.getReserved();
                        return a == null ? null : a.getAmount();
                    },
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case CREATED_AT_MS:
                return Comparator.comparing(
                    ReservationSummary::getCreatedAtMs, Comparator.nullsLast(Comparator.naturalOrder()));
            case EXPIRES_AT_MS:
                return Comparator.comparing(
                    ReservationSummary::getExpiresAtMs, Comparator.nullsLast(Comparator.naturalOrder()));
            default:
                throw new IllegalStateException("Unhandled sort_by: " + sortBy);
        }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
