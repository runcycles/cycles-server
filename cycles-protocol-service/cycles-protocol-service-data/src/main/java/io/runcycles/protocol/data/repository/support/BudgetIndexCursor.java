package io.runcycles.protocol.data.repository.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Keyset cursor for the index-backed balances listing: carries the last budget
 * key returned so the next page resumes strictly after it. The {@code bi1.}
 * prefix cannot collide with legacy SCAN cursors (all-digit or bare base64url
 * JSON), which lets the reader tell the two pagination modes apart.
 */
public final class BudgetIndexCursor {
    private static final String PREFIX = "bi1.";

    private BudgetIndexCursor() {}

    public static String encode(String lastKey) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(lastKey.getBytes(StandardCharsets.UTF_8));
    }

    /** @return the last key carried by an index cursor, or {@code null} if this is not one. */
    public static String decode(String cursor) {
        if (cursor == null || !cursor.startsWith(PREFIX)) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length())),
                StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
