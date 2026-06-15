package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.evidence.JwksDocuments;
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

    public JwksController(
            @Value("${cycles.evidence.signing.signer-did:}") String signerDid,
            @Value("${cycles.evidence.signing.kid:}") String kid,
            @Value("${cycles.evidence.signing.nbf-ms:0}") long nbfMs) {
        this.signerDid = signerDid == null ? "" : signerDid.trim();
        this.kid = kid == null ? "" : kid.trim();
        this.nbfMs = nbfMs;
        if (!this.signerDid.isBlank() && !JwksDocuments.isRawHexKey(this.signerDid)) {
            LOG.info("evidence signer_did is not a raw 64-hex key (did:cycles or other); JWKS "
                    + "publication needs a raw-hex public key, so GET /v1/.well-known/cycles-jwks.json "
                    + "will return 404 until one is configured");
        }
    }

    @GetMapping(value = "/cycles-jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getEvidenceJwks",
            summary = "Fetch the signer's CyclesEvidence JWK Set (signer-key resolution)")
    public ResponseEntity<Map<String, Object>> getEvidenceJwks() {
        Map<String, Object> jwks = JwksDocuments.jwkSet(signerDid, kid, nbfMs)
                .orElseThrow(() -> CyclesProtocolException.notFound("cycles-jwks.json"));
        // Short, public cache — the set changes only on key rotation, so unlike a
        // content-addressed envelope it MUST NOT be immutable.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(jwks);
    }
}
