package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the content-addressed {@code evidence_id} of a
 * {@code cycles-evidence/v0.1} envelope SYNCHRONOUSLY, on the request path.
 *
 * <p>The {@code evidence_id} is the sha256 of the RFC 8785 (JCS) canonical
 * bytes of the envelope with {@code evidence_id} and {@code signature} both
 * emptied — a pure function of the envelope contents that does NOT require the
 * private signing key. That lets cycles-server return the id on the lifecycle
 * response (so a caller can bind a receipt to it) while the actual Ed25519
 * signing + storage stay asynchronous in the event-tier worker.
 *
 * <p><b>Byte-for-byte parity is load-bearing.</b> This MUST assemble the
 * envelope identically to the event-tier {@code CyclesEvidenceEnvelopeBuilder}
 * (same field set, same {@code payload.<artifact_type>} nesting, same JCS impl)
 * or the id returned here will not match the envelope the worker signs and
 * stores, and a consumer would fetch a 404 / mismatched envelope. The worker
 * cross-checks the id it recomputes against the one stamped on the source
 * record and dead-letters on mismatch, so any drift fails closed rather than
 * silently serving an unbindable envelope.
 */
@Component
public final class EvidenceIdComputer {

    static final String SCHEMA_VERSION = "cycles-evidence/v0.1";

    private final ObjectMapper mapper;

    public EvidenceIdComputer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Compute the {@code evidence_id} for the envelope these facts would build.
     *
     * @param artifactType wire name (e.g. {@code reserve}); becomes both
     *                     {@code artifact_type} and the {@code payload} key
     * @param serverId     issuing-server URI ({@code server_id})
     * @param signerDid    Ed25519 public key hex ({@code signer_did}) — the
     *                     PUBLIC identity only; no private key is needed here
     * @param issuedAtMs   issuance clock ({@code issued_at_ms})
     * @param traceId      correlation id, or {@code null}/blank to omit
     * @param payloadBody  artifact-specific body, nested under
     *                     {@code payload.<artifactType>}
     * @return lowercase sha256 hex content id
     */
    public String compute(String artifactType, String serverId, String signerDid,
                          long issuedAtMs, String traceId, Object payloadBody) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schema_version", SCHEMA_VERSION);
        envelope.put("artifact_type", artifactType);
        envelope.put("server_id", serverId);
        envelope.put("signer_did", signerDid);
        envelope.put("issued_at_ms", issuedAtMs);
        if (traceId != null && !traceId.isBlank()) {
            envelope.put("trace_id", traceId);
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set(artifactType, mapper.valueToTree(payloadBody));
        envelope.set("payload", payload);
        envelope.put("evidence_id", "");
        envelope.put("signature", "");
        return sha256Hex(canonicalize(envelope));
    }

    private byte[] canonicalize(ObjectNode node) {
        try {
            return new JsonCanonicalizer(mapper.writeValueAsString(node)).getEncodedUTF8();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("envelope serialization failed", e);
        } catch (IOException e) {
            throw new IllegalStateException("JCS canonicalization failed", e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
