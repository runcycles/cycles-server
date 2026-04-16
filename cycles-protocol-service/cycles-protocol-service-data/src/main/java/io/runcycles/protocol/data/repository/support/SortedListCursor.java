package io.runcycles.protocol.data.repository.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Opaque cursor for sorted list endpoints (GET /v1/reservations with sort_by / sort_dir).
 *
 * <p>Spec: cycles-protocol-v0.yaml revision 2026-04-16 — cursors returned under a given
 * {@code (sort_by, sort_dir, filters)} tuple are only valid for continued pagination under
 * the same tuple. Servers MUST encode the sort key into the cursor so subsequent pages
 * stay in sort order.
 *
 * <p>Wire format: Base64URL-no-pad of a JSON object. Versioned via {@code v} so the format
 * can evolve without breaking clients mid-pagination.
 *
 * <p>Backward compatibility: {@link #decode(String)} returns {@link Optional#empty()} for
 * null, blank, or all-digit inputs — the all-digit case is the legacy Redis SCAN cursor
 * format that the non-sorted path continues to use.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SortedListCursor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    @JsonProperty("v") private int version;
    @JsonProperty("sb") private String sortBy;
    @JsonProperty("sd") private String sortDir;
    @JsonProperty("fh") private String filterHash;
    @JsonProperty("lsv") private String lastSortValue;
    @JsonProperty("lrid") private String lastReservationId;

    public SortedListCursor() {}

    public SortedListCursor(int version, String sortBy, String sortDir,
                            String filterHash, String lastSortValue, String lastReservationId) {
        this.version = version;
        this.sortBy = sortBy;
        this.sortDir = sortDir;
        this.filterHash = filterHash;
        this.lastSortValue = lastSortValue;
        this.lastReservationId = lastReservationId;
    }

    public int getVersion() { return version; }
    public String getSortBy() { return sortBy; }
    public String getSortDir() { return sortDir; }
    public String getFilterHash() { return filterHash; }
    public String getLastSortValue() { return lastSortValue; }
    public String getLastReservationId() { return lastReservationId; }

    public void setVersion(int v) { this.version = v; }
    public void setSortBy(String s) { this.sortBy = s; }
    public void setSortDir(String s) { this.sortDir = s; }
    public void setFilterHash(String s) { this.filterHash = s; }
    public void setLastSortValue(String s) { this.lastSortValue = s; }
    public void setLastReservationId(String s) { this.lastReservationId = s; }

    public String encode() {
        try {
            byte[] json = MAPPER.writeValueAsBytes(this);
            return B64_ENC.encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    /**
     * Decode a cursor string into a {@link SortedListCursor}.
     *
     * <p>Returns {@link Optional#empty()} for:
     * <ul>
     *   <li>null / blank input — no cursor supplied</li>
     *   <li>all-digit input (e.g. "0", "42") — legacy SCAN cursor from non-sorted path</li>
     *   <li>malformed Base64 or JSON — caller treats as legacy or rejects per policy</li>
     * </ul>
     */
    public static Optional<SortedListCursor> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Optional.empty();
        if (isAllDigits(cursor)) return Optional.empty();
        try {
            byte[] json = B64_DEC.decode(cursor);
            SortedListCursor decoded = MAPPER.readValue(
                new String(json, StandardCharsets.UTF_8), SortedListCursor.class);
            return Optional.of(decoded);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
