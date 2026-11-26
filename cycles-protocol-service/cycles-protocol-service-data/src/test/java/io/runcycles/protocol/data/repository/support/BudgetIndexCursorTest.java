package io.runcycles.protocol.data.repository.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BudgetIndexCursor")
class BudgetIndexCursorTest {

    @Test
    void roundTripsBudgetKey() {
        String key = "budget:tenant:acme/app:my-app:USD_MICROCENTS";
        String cursor = BudgetIndexCursor.encode(key);

        assertThat(cursor).startsWith("bi1.");
        assertThat(BudgetIndexCursor.decode(cursor)).isEqualTo(key);
    }

    @Test
    void nonIndexCursorsDecodeToNull() {
        assertThat(BudgetIndexCursor.decode(null)).isNull();
        assertThat(BudgetIndexCursor.decode("")).isNull();
        // legacy SCAN cursors: raw Redis cursor and base64url page cursor
        assertThat(BudgetIndexCursor.decode("17")).isNull();
        assertThat(BudgetIndexCursor.decode("eyJjIjoiMCIsIm8iOjF9")).isNull();
    }

    @Test
    void malformedIndexCursorDecodesToNull() {
        assertThat(BudgetIndexCursor.decode("bi1.***not-base64***")).isNull();
    }
}
