package io.runcycles.protocol.data.repository.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Cursor for legacy SCAN-backed list endpoints.
 *
 * <p>Redis SCAN cursors point to the next batch, not the next key. When an API
 * page limit is reached before consuming the full returned batch, the next page
 * must resume from the same Redis cursor and skip keys already inspected in
 * that batch. Numeric Redis cursors are still accepted for backwards
 * compatibility. Like any Redis SCAN iteration, this is best-effort rather
 * than snapshot-isolated when the keyspace changes or Redis rehashes between
 * page requests.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanPageCursor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    @JsonProperty("v") private int version;
    @JsonProperty("t") private String type;
    @JsonProperty("c") private String redisCursor;
    @JsonProperty("o") private int offset;

    public ScanPageCursor() {}

    private ScanPageCursor(String redisCursor, int offset) {
        this.version = 1;
        this.type = "scan";
        this.redisCursor = redisCursor;
        this.offset = offset;
    }

    public String redisCursor() {
        return redisCursor == null || redisCursor.isBlank() ? "0" : redisCursor;
    }

    public int offset() {
        return Math.max(offset, 0);
    }

    public static ScanPageCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new ScanPageCursor("0", 0);
        }
        if (isAllDigits(cursor)) {
            return new ScanPageCursor(cursor, 0);
        }
        try {
            byte[] json = B64_DEC.decode(cursor);
            ScanPageCursor decoded = MAPPER.readValue(
                new String(json, StandardCharsets.UTF_8), ScanPageCursor.class);
            if (decoded.version == 1
                && "scan".equals(decoded.type)
                && decoded.redisCursor != null
                && decoded.offset >= 0) {
                return decoded;
            }
        } catch (Exception ignored) {
            // Preserve legacy behavior: malformed non-sorted cursors are passed to Redis.
        }
        return new ScanPageCursor(cursor, 0);
    }

    public static String nextCursor(String currentRedisCursor,
                                    int nextBatchOffset,
                                    int batchSize,
                                    String nextRedisCursor) {
        if (nextBatchOffset < batchSize) {
            return new ScanPageCursor(currentRedisCursor, nextBatchOffset).encode();
        }
        return "0".equals(nextRedisCursor) ? null : nextRedisCursor;
    }

    private String encode() {
        try {
            byte[] json = MAPPER.writeValueAsBytes(this);
            return B64_ENC.encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode SCAN cursor", e);
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
