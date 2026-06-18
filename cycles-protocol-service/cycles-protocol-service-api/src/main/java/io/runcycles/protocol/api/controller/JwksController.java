package io.runcycles.protocol.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.api.evidence.JwksDocuments;
import io.runcycles.protocol.api.evidence.JwksDocuments.RetiredKey;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the signer's CyclesEvidence JWK Set (cycles-protocol-v0
 * {@code getEvidenceJwks}) — the publication half of the additive
 * signer-key-resolution layer (cycles-evidence v0.2).
 *
 * <p>No auth — see {@code SecurityConfig.PUBLIC_PATHS} and the spec's
 * {@code getEvidenceJwks} description: a JWK Set is public keys only (the
 * private signing key is never served), and the set is itself the trust anchor
 * consumers resolve, so it must be reachable without credentials.
 *
 * <p>Located API-base-relative at {@code /v1/.well-known/cycles-jwks.json}
 * (the spec path is {@code {server_id}/.well-known/cycles-jwks.json} and
 * {@code server_id} already carries {@code /v1}) — deliberately NOT
 * origin-rooted, so key authority stays anchored to the base the
 * {@code did:cycles} hash commits to.
 *
 * <p>Reads the public signing identity ({@code signer-did}) directly via
 * {@code @Value} — the same shared properties the worker and {@code EvidenceEmitter}
 * use — plus the JWK {@code kid} and {@code cycles_nbf_ms}. Holding no injected
 * collaborator keeps it loadable in any context without extra wiring.
 */
@RestController
@RequestMapping("/v1/.well-known")
@Tag(name = "Evidence")
public class JwksController {

    private static final Logger LOG = LoggerFactory.getLogger(JwksController.class);

    private final String signerDid;
    private final String kid;
    private final long nbfMs;
    private final List<RetiredKey> retiredKeys;

    public JwksController(
            @Value("${cycles.evidence.signing.signer-did:}") String signerDid,
            @Value("${cycles.evidence.signing.kid:}") String kid,
            @Value("${cycles.evidence.signing.nbf-ms:0}") long nbfMs,
            @Value("${cycles.evidence.signing.retired-keys:}") String retiredKeysJson) {
        this.signerDid = signerDid == null ? "" : signerDid.trim();
        this.kid = kid == null ? "" : kid.trim();
        this.nbfMs = nbfMs;
        this.retiredKeys = parseRetiredKeys(retiredKeysJson);
        if (!this.signerDid.isBlank() && !JwksDocuments.isRawHexKey(this.signerDid)) {
            LOG.info("evidence signer_did is not a raw 64-hex key (did:cycles or other); JWKS "
                    + "publication needs a raw-hex public key, so GET /v1/.well-known/cycles-jwks.json "
                    + "will return 404 until one is configured");
        }
        if (!this.retiredKeys.isEmpty()) {
            LOG.info("evidence JWKS: {} retired key(s) configured for rotation history", this.retiredKeys.size());
            if (activeKeyWindowPredatesRetirement(this.nbfMs, this.retiredKeys)) {
                LOG.warn("evidence JWKS: configured active key cycles_nbf_ms ({}) is at/before a retired key's "
                        + "window end; the published active window is advanced to the latest retired exp so the "
                        + "current key cannot resolve as valid for pre-rotation evidence. Set "
                        + "cycles.evidence.signing.nbf-ms to the rotation time to make this explicit.", this.nbfMs);
            }
        }
    }

    /**
     * True when the active key's {@code nbf-ms} starts before a retired key's
     * window ends — i.e. retired keys exist (a rotation happened) but the active
     * key's window was not advanced to the rotation time, so the active key is
     * still published as authoritative for pre-rotation {@code issued_at_ms}.
     */
    static boolean activeKeyWindowPredatesRetirement(long activeNbfMs, List<RetiredKey> retired) {
        long latestRetiredExp = retired.stream()
                .filter(r -> r != null && r.expMs() != null && r.expMs() > r.nbfMs())
                .mapToLong(RetiredKey::expMs)
                .max()
                .orElse(Long.MIN_VALUE);
        return activeNbfMs < latestRetiredExp;
    }

    /**
     * Parse {@code cycles.evidence.signing.retired-keys} — a JSON array of
     * {@code {"signer_did","kid","nbf_ms","exp_ms"}} — into retired-key records.
     * Malformed/incomplete entries are dropped here (logged) or skipped later by
     * {@link JwksDocuments}; a parse failure yields no retired keys (the active
     * key still publishes), never a crash.
     */
    private static List<RetiredKey> parseRetiredKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<RetiredKey> out = new ArrayList<>();
        try {
            JsonNode arr = new ObjectMapper().readTree(json);
            if (!arr.isArray()) {
                LOG.warn("cycles.evidence.signing.retired-keys is not a JSON array; ignoring");
                return List.of();
            }
            for (JsonNode n : arr) {
                JsonNode nbfNode = n.path("nbf_ms");
                JsonNode expNode = n.path("exp_ms");
                // Both window bounds MUST be explicit integral epoch-ms. A missing
                // or non-integral nbf_ms is NOT coerced to 0 (epoch) — that would
                // silently widen the validity window; drop the entry instead.
                if (!nbfNode.isIntegralNumber() || !expNode.isIntegralNumber()) {
                    LOG.warn("retired key '{}' has a missing/non-integral nbf_ms or exp_ms; skipping",
                            n.path("kid").asText(""));
                    continue;
                }
                out.add(new RetiredKey(
                        n.path("signer_did").asText(""),
                        n.path("kid").asText(""),
                        nbfNode.asLong(),
                        expNode.asLong()));
            }
        } catch (Exception e) {
            LOG.warn("could not parse cycles.evidence.signing.retired-keys; ignoring: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    @GetMapping(value = "/cycles-jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getEvidenceJwks",
            summary = "Fetch the signer's CyclesEvidence JWK Set (signer-key resolution)")
    public ResponseEntity<Map<String, Object>> getEvidenceJwks() {
        Map<String, Object> jwks = JwksDocuments.jwkSet(signerDid, kid, nbfMs, retiredKeys)
                .orElseThrow(() -> CyclesProtocolException.notFound("cycles-jwks.json"));
        // Short, public cache — the set changes only on key rotation, so unlike a
        // content-addressed envelope it MUST NOT be immutable.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(jwks);
    }
}
