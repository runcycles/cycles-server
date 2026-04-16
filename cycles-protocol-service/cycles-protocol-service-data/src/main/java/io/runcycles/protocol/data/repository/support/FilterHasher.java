package io.runcycles.protocol.data.repository.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable hash of the filter tuple on listReservations, used as the {@code fh} field in
 * {@link SortedListCursor} to detect when a client reuses a cursor under a different filter set.
 *
 * <p>SHA-256 truncated to the first 16 hex chars (8 bytes). This is NOT a security boundary —
 * it's a server-side check to reject mismatched cursor reuse with 400 INVALID_REQUEST.
 * 8-byte truncation keeps cursor length bounded while retaining collision resistance far
 * beyond what's needed for this use case.
 *
 * <p>Canonical form: fields serialized in a fixed order so hashes are deterministic regardless
 * of query-parameter ordering on the wire.
 */
public final class FilterHasher {
    private FilterHasher() {}

    public static String hash(String tenant, String idempotencyKey, String status,
                              String workspace, String app, String workflow,
                              String agent, String toolset) {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append("t=").append(nullSafe(tenant)).append('|');
        canonical.append("i=").append(nullSafe(idempotencyKey)).append('|');
        canonical.append("st=").append(nullSafe(status)).append('|');
        canonical.append("ws=").append(nullSafe(workspace)).append('|');
        canonical.append("ap=").append(nullSafe(app)).append('|');
        canonical.append("wf=").append(nullSafe(workflow)).append('|');
        canonical.append("ag=").append(nullSafe(agent)).append('|');
        canonical.append("ts=").append(nullSafe(toolset));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
