package io.runcycles.protocol.data.util;

/**
 * Flattens user-controlled values before they reach a log line so that
 * embedded {@code CR}/{@code LF} characters cannot forge additional log
 * entries (log injection). Shared by the API and data modules so every layer
 * applies the same sanitization — the runtime plane logs request-derived
 * strings (tenant, reservation id, scope, exception messages) from both.
 *
 * <p>Returns {@code null} unchanged so it composes with SLF4J's {@code {}}
 * placeholders without adding a literal {@code "null"} where the caller wants
 * a real null. Non-strings are rendered via {@link String#valueOf(Object)}.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /**
     * @return the value with {@code \r} and {@code \n} replaced by spaces, or
     *         {@code null} if {@code value} is null.
     */
    public static String sanitize(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().replace('\r', ' ').replace('\n', ' ');
    }
}
