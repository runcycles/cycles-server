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
                              String agent, String toolset,
                              Long fromMs, Long toMs,
                              Long expiresFromMs, Long expiresToMs,
                              Long finalizedFromMs, Long finalizedToMs) {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append("t=").append(nullSafe(tenant)).append('|');
        canonical.append("i=").append(nullSafe(idempotencyKey)).append('|');
        canonical.append("st=").append(nullSafe(status)).append('|');
        canonical.append("ws=").append(nullSafe(workspace)).append('|');
        canonical.append("ap=").append(nullSafe(app)).append('|');
        canonical.append("wf=").append(nullSafe(workflow)).append('|');
        canonical.append("ag=").append(nullSafe(agent)).append('|');
        canonical.append("ts=").append(nullSafe(toolset));
        // Back-compat: each window pair only emits its canonical block when at
        // least one of its bounds is set. A canonical form that always carried
        // every window's |...=|...= chunks would change the hash for every
        // pre-window cursor (a v0.1.25.18 sorted-path cursor mid-pagination
        // across the deployment, a v0.1.25.20 cursor with from/to set but
        // no expires_*/finalized_*, etc.), breaking the wire back-compat
        // guarantee. Independent gating preserves byte-exact hashes for
        // every prior generation of cursor that didn't carry the supplied
        // bounds, while still uniquely identifying any new combination.
        //
        // v0.1.25.20 added the `fr=`/`to=` pair (created_at_ms window).
        // v0.1.25.21 adds `ef=`/`et=` (expires_at_ms) and `ff=`/`ft=`
        // (finalized_at_ms) per cycles-protocol-v0.yaml revision 2026-05-22.
        if (fromMs != null || toMs != null) {
            canonical.append('|');
            canonical.append("fr=").append(nullSafeLong(fromMs)).append('|');
            canonical.append("to=").append(nullSafeLong(toMs));
        }
        if (expiresFromMs != null || expiresToMs != null) {
            canonical.append('|');
            canonical.append("ef=").append(nullSafeLong(expiresFromMs)).append('|');
            canonical.append("et=").append(nullSafeLong(expiresToMs));
        }
        if (finalizedFromMs != null || finalizedToMs != null) {
            canonical.append('|');
            canonical.append("ff=").append(nullSafeLong(finalizedFromMs)).append('|');
            canonical.append("ft=").append(nullSafeLong(finalizedToMs));
        }
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

    private static String nullSafeLong(Long v) {
        return v == null ? "" : Long.toString(v);
    }
}
