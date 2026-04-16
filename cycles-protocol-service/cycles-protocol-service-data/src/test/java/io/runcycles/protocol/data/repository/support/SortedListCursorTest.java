package io.runcycles.protocol.data.repository.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SortedListCursor")
class SortedListCursorTest {

    @Test
    @DisplayName("encode then decode round-trips every field")
    void roundTrip() {
        SortedListCursor c = new SortedListCursor(1, "status", "asc",
                "a3f1c9e84200deadbeef".substring(0, 16),
                "COMMITTED", "res_abc123");

        String encoded = c.encode();
        Optional<SortedListCursor> back = SortedListCursor.decode(encoded);

        assertThat(back).isPresent();
        assertThat(back.get().getVersion()).isEqualTo(1);
        assertThat(back.get().getSortBy()).isEqualTo("status");
        assertThat(back.get().getSortDir()).isEqualTo("asc");
        assertThat(back.get().getFilterHash()).isEqualTo("a3f1c9e84200dead");
        assertThat(back.get().getLastSortValue()).isEqualTo("COMMITTED");
        assertThat(back.get().getLastReservationId()).isEqualTo("res_abc123");
    }

    @Test
    @DisplayName("decode null or blank returns empty")
    void decodeBlank() {
        assertThat(SortedListCursor.decode(null)).isEmpty();
        assertThat(SortedListCursor.decode("")).isEmpty();
        assertThat(SortedListCursor.decode("   ")).isEmpty();
    }

    @Test
    @DisplayName("decode all-digit input returns empty (legacy SCAN cursor passthrough)")
    void decodeLegacyScanCursor() {
        // Preserves backward compat: legacy clients use Redis SCAN cursors which are
        // stringified integers. These must NOT be parsed as sorted cursors.
        assertThat(SortedListCursor.decode("0")).isEmpty();
        assertThat(SortedListCursor.decode("42")).isEmpty();
        assertThat(SortedListCursor.decode("1234567890")).isEmpty();
    }

    @Test
    @DisplayName("decode malformed base64 returns empty")
    void decodeMalformed() {
        assertThat(SortedListCursor.decode("!!!not-base64!!!")).isEmpty();
        assertThat(SortedListCursor.decode("SGVsbG8gV29ybGQ=")).isEmpty(); // valid b64 but not JSON
    }

    @Test
    @DisplayName("encoded cursor survives ASCII printable transit (URL-safe Base64, no padding)")
    void cursorShapeIsUrlSafe() {
        SortedListCursor c = new SortedListCursor(1, "created_at_ms", "desc",
                "0000000000000000", "1700000000000", "res_1");
        String encoded = c.encode();
        assertThat(encoded).doesNotContain("=");
        assertThat(encoded).doesNotContain("+");
        assertThat(encoded).doesNotContain("/");
    }
}
