package io.runcycles.protocol.api.evidence;

import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the {@code CyclesEvidenceJwks} document served by
 * {@code getEvidenceJwks} ({@code GET /v1/.well-known/cycles-jwks.json}) — the
 * publication half of the additive signer-key-resolution layer
 * (cycles-evidence v0.2). Pure function over the configured signing identity;
 * no Spring, no I/O.
 *
 * <p>v0.1 scope: publishes the single currently-configured RAW-HEX
 * {@code signer_did} as one active Ed25519 OKP JWK. A {@code did:cycles}
 * {@code signer_did} carries no key bytes, so it cannot be published from
 * {@code signer_did} alone — that (and retired-key rotation history) is the
 * v0.2-store follow-up; until then this returns {@link Optional#empty()} and
 * the endpoint 404s, leaving consumers on the raw-hex + {@code expected_signer}
 * pinning path.
 *
 * <p>Key bytes match what {@code EnvelopeSigner} signs with: the JWK {@code x}
 * is {@code base64url(hex-decode(signer_did))}, the same 32 raw public-key
 * bytes — so a verifier resolving this set authenticates the same signatures.
 */
public final class JwksDocuments {

    private static final Pattern RAW_HEX_32 = Pattern.compile("[0-9a-fA-F]{64}");

    private JwksDocuments() {
    }

    /** True when {@code signerDid} is a publishable raw 64-hex Ed25519 key. */
    public static boolean isRawHexKey(String signerDid) {
        return signerDid != null && RAW_HEX_32.matcher(signerDid.trim()).matches();
    }

    /**
     * The signer's JWK Set, or empty when no raw-hex signing key is configured
     * (blank, or a {@code did:cycles} form that carries no key bytes).
     *
     * @param signerDid the configured {@code cycles.evidence.signing.signer-did}
     * @param kid       configured key id, or blank to derive a stable default
     * @param nbfMs     {@code cycles_nbf_ms} validity-from (epoch ms, inclusive)
     */
    public static Optional<Map<String, Object>> jwkSet(String signerDid, String kid, long nbfMs) {
        if (!isRawHexKey(signerDid)) {
            return Optional.empty();
        }
        String did = signerDid.trim();
        byte[] publicKey = HexFormat.of().parseHex(did);
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey);

        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("alg", "EdDSA");
        jwk.put("x", x);
        jwk.put("kid", (kid == null || kid.isBlank()) ? defaultKid(did) : kid.trim());
        jwk.put("cycles_nbf_ms", nbfMs);
        // cycles_exp_ms omitted ⇒ active (open-ended); `status` is advisory only —
        // selection is by validity window, never by status.
        jwk.put("status", "active");

        Map<String, Object> jwks = new LinkedHashMap<>();
        jwks.put("keys", List.of(jwk));
        return Optional.of(jwks);
    }

    /** Stable default key id when none is configured: the first 16 hex chars of
     *  the (lowercased) public key — deterministic and stable per key. */
    private static String defaultKid(String didHex) {
        return didHex.substring(0, 16).toLowerCase();
    }
}
