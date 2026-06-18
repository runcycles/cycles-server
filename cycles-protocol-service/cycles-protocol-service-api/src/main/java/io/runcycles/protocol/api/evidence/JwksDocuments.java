package io.runcycles.protocol.api.evidence;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the {@code CyclesEvidenceJwks} document served by
 * {@code getEvidenceJwks} ({@code GET /v1/.well-known/cycles-jwks.json}) — the
 * publication half of the additive signer-key-resolution layer
 * (cycles-evidence v0.2). Pure function over the configured signing identity;
 * no Spring, no I/O.
 *
 * <p>Publishes the currently-configured RAW-HEX {@code signer_did} as the single
 * ACTIVE Ed25519 OKP JWK (open-ended window), PLUS any configured RETIRED keys —
 * each with a bounded {@code [cycles_nbf_ms, cycles_exp_ms)} window — so that
 * evidence signed before a key rotation still verifies against the key that was
 * valid at its {@code issued_at_ms}. Retaining retired keys is the load-bearing
 * rotation rule: a verifier selects the key whose window covers the envelope's
 * issuance time, never "the current key". A {@code did:cycles} {@code signer_did}
 * carries no key bytes, so the active key cannot be published from it alone;
 * the set is empty (endpoint 404s) until a raw-hex active key is configured.
 *
 * <p>Key bytes match what {@code EnvelopeSigner} signs with: a JWK {@code x} is
 * {@code base64url(hex-decode(<raw-hex pubkey>))}, the same 32 raw bytes — so a
 * verifier resolving this set authenticates the same signatures.
 */
public final class JwksDocuments {

    private static final Pattern RAW_HEX_32 = Pattern.compile("[0-9a-fA-F]{64}");

    private JwksDocuments() {
    }

    /**
     * A previously-active signing key, retained in the published set so evidence
     * signed during its validity window still resolves after rotation.
     *
     * @param signerDid the retired key as a raw 64-hex Ed25519 public key
     * @param kid       its stable key id (must be unique across the set)
     * @param nbfMs     valid-from (epoch ms, inclusive)
     * @param expMs     valid-until (epoch ms, EXCLUSIVE) — REQUIRED for a retired
     *                  key (a retired key has a closed window); a null exp is a
     *                  config error and the entry is skipped.
     */
    public record RetiredKey(String signerDid, String kid, long nbfMs, Long expMs) {
    }

    /** True when {@code signerDid} is a publishable raw 64-hex Ed25519 key. */
    public static boolean isRawHexKey(String signerDid) {
        return signerDid != null && RAW_HEX_32.matcher(signerDid.trim()).matches();
    }

    /** Single active-key set (no rotation history). */
    public static Optional<Map<String, Object>> jwkSet(String signerDid, String kid, long nbfMs) {
        return jwkSet(signerDid, kid, nbfMs, List.of());
    }

    /**
     * The signer's JWK Set — the active key plus any retired keys — or empty
     * when no raw-hex active key is configured. Invalid retired entries are
     * skipped defensively (a bad history entry never breaks publication of the
     * active key): malformed hex; a missing {@code expMs} (a retired key needs a
     * closed window); an empty/inverted window ({@code expMs <= nbfMs}, since
     * {@code cycles_exp_ms} is EXCLUSIVE); the SAME key material as an earlier
     * retired key with an OVERLAPPING window — raw-hex selection by key bytes
     * plus {@code issued_at_ms} would be ambiguous, though disjoint windows for
     * a reused key are fine; or a {@code kid} colliding with the active key or an
     * earlier retired key (a duplicate {@code kid} is never emitted — set-wide
     * kid uniqueness is required).
     *
     * @param signerDid the active key ({@code cycles.evidence.signing.signer-did})
     * @param kid       active key id, or blank to derive a stable default
     * @param nbfMs     active {@code cycles_nbf_ms} (epoch ms, inclusive); if it
     *                  was left below the latest retired key's {@code exp_ms} the
     *                  published active window is advanced to that boundary, so the
     *                  active key is never valid for pre-rotation {@code issued_at_ms}
     * @param retired   retired keys to retain in the set (may be empty/null)
     */
    public static Optional<Map<String, Object>> jwkSet(
            String signerDid, String kid, long nbfMs, List<RetiredKey> retired) {
        if (!isRawHexKey(signerDid)) {
            return Optional.empty();
        }
        String activeDid = signerDid.trim();
        String activeKid = (kid == null || kid.isBlank()) ? defaultKid(activeDid) : kid.trim();

        // Safety floor (fail-safe, not just a warning): the active key MUST NOT be
        // published as valid before the latest retired key's window ends, or the
        // current key could sign a backdated envelope (issued_at_ms before the
        // rotation) that still resolves as authentic. If the configured nbf-ms was
        // left below that boundary, advance the published active window up to it.
        // Floor on EVERY declared bounded retired window — even one whose key
        // material is malformed (so it won't be published): a typo in rotation
        // history must not reopen the pre-rotation backdating hole on the active key.
        long latestRetiredExp = (retired == null ? List.<RetiredKey>of() : retired).stream()
                .filter(r -> r != null && r.expMs() != null && r.expMs() > r.nbfMs())
                .mapToLong(RetiredKey::expMs)
                .max()
                .orElse(Long.MIN_VALUE);
        long activeNbf = Math.max(nbfMs, latestRetiredExp);

        List<Map<String, Object>> keys = new ArrayList<>();
        Set<String> kids = new LinkedHashSet<>();
        // Emitted [nbf, exp) windows per key material (lowercased hex), so the same
        // key republished with an OVERLAPPING window is never emitted twice — raw-hex
        // selection is key-bytes + issued_at_ms, which would otherwise be ambiguous.
        // The active key needs no entry: the nbf clamp above puts its window at/after
        // every retired exp, so it is always disjoint from a same-material retired key.
        Map<String, List<long[]>> windowsByMaterial = new HashMap<>();
        keys.add(buildJwk(activeDid, activeKid, activeNbf, null, "active"));
        kids.add(activeKid);

        if (retired != null) {
            for (RetiredKey r : retired) {
                if (r == null || !isRawHexKey(r.signerDid()) || r.expMs() == null) {
                    continue; // malformed hex or no closed window — skip
                }
                long rNbf = r.nbfMs();
                long rExp = r.expMs();
                if (rExp <= rNbf) {
                    continue; // empty or inverted window (exp is EXCLUSIVE) — skip
                }
                String rDid = r.signerDid().trim();
                String material = rDid.toLowerCase();
                List<long[]> seen = windowsByMaterial.get(material);
                if (seen != null && seen.stream().anyMatch(w -> rNbf < w[1] && w[0] < rExp)) {
                    continue; // same key material with an overlapping window (active or retired) — ambiguous, skip
                }
                String rKid = (r.kid() == null || r.kid().isBlank()) ? defaultKid(rDid) : r.kid().trim();
                if (!kids.add(rKid)) {
                    continue; // duplicate kid — never emit (set-wide uniqueness)
                }
                keys.add(buildJwk(rDid, rKid, rNbf, rExp, "retired"));
                windowsByMaterial.computeIfAbsent(material, k -> new ArrayList<>()).add(new long[]{rNbf, rExp});
            }
        }

        Map<String, Object> jwks = new LinkedHashMap<>();
        jwks.put("keys", keys);
        return Optional.of(jwks);
    }

    /** One Ed25519 OKP JWK. {@code expMs == null} ⇒ active (open-ended, exp
     *  omitted); otherwise a bounded window with {@code status: retired}. */
    private static Map<String, Object> buildJwk(String didHex, String kid, long nbfMs, Long expMs, String status) {
        byte[] publicKey = HexFormat.of().parseHex(didHex);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("alg", "EdDSA");
        jwk.put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey));
        jwk.put("kid", kid);
        jwk.put("cycles_nbf_ms", nbfMs);
        if (expMs != null) {
            jwk.put("cycles_exp_ms", expMs);
        }
        // `status` is advisory only — selection is by validity window, never by status.
        jwk.put("status", status);
        return jwk;
    }

    /** Stable default key id when none is configured: the first 16 hex chars of
     *  the (lowercased) public key — deterministic and stable per key. */
    private static String defaultKid(String didHex) {
        return didHex.substring(0, 16).toLowerCase();
    }
}
