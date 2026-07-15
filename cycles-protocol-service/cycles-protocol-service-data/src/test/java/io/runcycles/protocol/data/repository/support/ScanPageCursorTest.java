package io.runcycles.protocol.data.repository.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScanPageCursor")
class ScanPageCursorTest {

    @Test
    void blankCursorStartsAtZero() {
        ScanPageCursor cursor = ScanPageCursor.decode(null);

        assertThat(cursor.redisCursor()).isEqualTo("0");
        assertThat(cursor.offset()).isZero();

        cursor = ScanPageCursor.decode(" ");
        assertThat(cursor.redisCursor()).isEqualTo("0");
        assertThat(cursor.offset()).isZero();
    }

    @Test
    void numericCursorPreservesLegacyRedisCursor() {
        ScanPageCursor cursor = ScanPageCursor.decode("42");

        assertThat(cursor.redisCursor()).isEqualTo("42");
        assertThat(cursor.offset()).isZero();
    }

    @Test
    void midBatchCursorRoundTripsCurrentCursorAndOffset() {
        String encoded = ScanPageCursor.nextCursor("17", 3, 10, "42");

        assertThat(encoded).isNotBlank();
        assertThat(encoded).isNotEqualTo("42");

        ScanPageCursor decoded = ScanPageCursor.decode(encoded);
        assertThat(decoded.redisCursor()).isEqualTo("17");
        assertThat(decoded.offset()).isEqualTo(3);
    }

    @Test
    void batchEndReturnsNextRedisCursorOrNullAtEndOfScan() {
        assertThat(ScanPageCursor.nextCursor("17", 10, 10, "42")).isEqualTo("42");
        assertThat(ScanPageCursor.nextCursor("17", 10, 10, "0")).isNull();
    }

    @Test
    void malformedCursorFallsBackToLegacyRedisCursor() {
        ScanPageCursor cursor = ScanPageCursor.decode("not-a-valid-cursor");

        assertThat(cursor.redisCursor()).isEqualTo("not-a-valid-cursor");
        assertThat(cursor.offset()).isZero();
    }

    @Test
    void encodedCursorWithWrongTypeFallsBackToLegacyRedisCursor() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":1,\"t\":\"other\",\"c\":\"17\",\"o\":1}".getBytes(StandardCharsets.UTF_8));

        ScanPageCursor cursor = ScanPageCursor.decode(encoded);

        assertThat(cursor.redisCursor()).isEqualTo(encoded);
        assertThat(cursor.offset()).isZero();
    }

    @Test
    void encodedCursorRejectsEveryInvalidFieldCombination() {
        assertFallsBack("{\"v\":2,\"t\":\"scan\",\"c\":\"17\",\"o\":1}");
        assertFallsBack("{\"v\":1,\"t\":\"scan\",\"c\":null,\"o\":1}");
        assertFallsBack("{\"v\":1,\"t\":\"scan\",\"c\":\"17\",\"o\":-1}");
    }

    @Test
    void blankRedisCursorAndNegativeOffsetNormalizeAtTheBoundary() {
        ScanPageCursor cursor = new ScanPageCursor();

        assertThat(cursor.redisCursor()).isEqualTo("0");
        assertThat(cursor.offset()).isZero();

        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"v\":1,\"t\":\"scan\",\"c\":\" \",\"o\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(ScanPageCursor.decode(encoded).redisCursor()).isEqualTo("0");
    }

    private static void assertFallsBack(String json) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        ScanPageCursor cursor = ScanPageCursor.decode(encoded);
        assertThat(cursor.redisCursor()).isEqualTo(encoded);
        assertThat(cursor.offset()).isZero();
    }
}
