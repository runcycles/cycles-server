package io.runcycles.protocol.data.util;

import java.security.SecureRandom;

/**
 * W3C Trace Context-compatible trace_id generator.
 *
 * <p>Pure helper, no Spring / servlet dependencies so background sweepers
 * and test harnesses can call it without any web-layer context.
 */
public final class TraceIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";

    private TraceIdGenerator() {}

    /**
     * Generate a fresh 128-bit trace_id as 32 lowercase hex characters.
     * Re-rolls the all-zero value per W3C Trace Context §3.2.2.3.
     */
    public static String generate() {
        while (true) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            String hex = toHex(bytes);
            if (!ALL_ZERO_TRACE_ID.equals(hex)) return hex;
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
