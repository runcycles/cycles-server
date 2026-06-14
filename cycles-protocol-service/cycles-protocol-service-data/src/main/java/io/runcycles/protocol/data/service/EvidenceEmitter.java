package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.EvidenceQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits CyclesEvidence SOURCE records — the operational facts a signed
 * {@code cycles-evidence/v0.1} envelope wraps — onto a dedicated Redis queue
 * for the event-tier worker to canonicalize, sign (with the server's Ed25519
 * identity), store and serve.
 *
 * <p>This server stamps the operational facts ({@code artifact_type},
 * {@code issued_at_ms}, {@code trace_id}, payload) AND — when the public server
 * identity ({@code cycles.evidence.server-id} + {@code cycles.evidence.signer-did})
 * is configured — computes the {@code evidence_id} SYNCHRONOUSLY via
 * {@link EvidenceIdComputer} and stamps it on the record. The {@code evidence_id}
 * is a pure function of the envelope contents (no private key needed), so it can
 * be returned on the lifecycle response ({@link EvidenceRef}) for a caller to
 * bind a receipt to, while the actual Ed25519 signing + storage stay async in
 * the event-tier worker, which recomputes the id and cross-checks it against the
 * stamped one (dead-lettering on mismatch) so drift fails closed.
 *
 * <p>The payload body shape is the caller's responsibility because it varies by
 * artifact type (cycles-evidence-v0.1): {@code reserve}/{@code decide} carry
 * {@code {request, response}}, {@code commit}/{@code release} additionally
 * carry {@code reservation_id}, and {@code error} carries
 * {@code {endpoint, http_status, request, response}}.
 *
 * <p>DURABILITY: the enqueue is SYNCHRONOUS — the source record is LPUSH'd
 * before the lifecycle response returns, so a successful operation cannot return
 * without its evidence being durably queued. The enqueue targets the same Redis
 * as the ledger write that just committed, so it succeeds whenever the operation
 * did (the loss window shrinks to a microsecond same-Redis gap). Only the cheap
 * enqueue is on the request path; the expensive work (JCS canonicalization +
 * Ed25519 signing) stays asynchronous in the event-tier worker.
 *
 * <p>FAIL-OPEN: if the push fails (e.g. Redis died just after the ledger commit)
 * it is logged and metered ({@link CyclesMetrics#recordEvidenceEmitFailed}) and
 * NEVER fails the already-committed response — failing the response after the
 * budget was reserved would be strictly worse than a rare evidence gap.
 */
@Service
public class EvidenceEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceEmitter.class);

    @Autowired
    private EvidenceQueueRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CyclesMetrics metrics;

    @Autowired
    private EvidenceIdComputer evidenceIdComputer;

    /**
     * Evidence-only serializer that OMITS null-valued properties. The evidence
     * mirror schemas (cycles-evidence-v0.1) declare every field with a concrete
     * type and {@code additionalProperties: false} — none are nullable — so a
     * serialized {@code null} (e.g. a reserve request's unset {@code ttl_ms},
     * {@code overage_policy}, {@code metadata}; a commit's {@code metrics}; a
     * release's {@code reason}) would make the signed envelope fail mirror
     * validation. The request/response DTOs cannot carry {@code @JsonInclude}
     * themselves (the SAME DTOs are serialized by the shared mapper for
     * idempotency payload hashes, reserve.lua args, and cached response bodies —
     * changing their byte shape would break those), so null-stripping is applied
     * HERE, to the evidence payload only, via a private NON_NULL copy of the
     * shared mapper. The shared mapper is untouched.
     */
    private volatile ObjectMapper evidencePayloadMapper;

    /** Lazily derive the NON_NULL serializer from the shared mapper on first use
     *  (the shared bean is injected after construction; no Spring lifecycle hook
     *  needed, and it works identically under a plain unit-test wiring). */
    private ObjectMapper evidencePayloadMapper() {
        ObjectMapper m = evidencePayloadMapper;
        if (m == null) {
            m = objectMapper.copy()
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
            evidencePayloadMapper = m;
        }
        return m;
    }

    /** Public issuing-server URI stamped as {@code server_id}; also the base of
     *  {@code cycles_evidence_url}. Must match the event-tier worker's value. */
    @Value("${cycles.evidence.server-id:}")
    private String serverId;

    /** Public Ed25519 key hex stamped as {@code signer_did}. Must match the
     *  public half of the worker's signing key. PUBLIC only — never the key.
     *  Same property as the event-tier worker ({@code cycles.evidence.signing.signer-did}
     *  / {@code EVIDENCE_SIGNING_SIGNER_DID}) so one env var configures both services. */
    @Value("${cycles.evidence.signing.signer-did:}")
    private String signerDid;

    /**
     * Synchronously enqueue an evidence-source record for a lifecycle artifact
     * and, when the public server identity is configured, compute its
     * {@code evidence_id} and return a {@link EvidenceRef} for the caller to
     * surface on the response. Call AFTER the ledger write commits and BEFORE
     * returning the response.
     *
     * @param artifactType decide / reserve / commit / release / error
     * @param issuedAtMs   the event's issuance clock (epoch millis), stamped at
     *                     RESPONSE time — the authoritative event time
     * @param traceId      correlation id, or {@code null}/blank to omit
     * @param payloadBody  the artifact-specific body — becomes
     *                     {@code payload.<artifactType>} (see class javadoc)
     * @return the evidence reference ({@code evidence_id} + {@code cycles_evidence_url})
     *         when the id could be computed, or {@code null} if the server
     *         identity is unconfigured or emission failed (fail-open — never throws)
     */
    public EvidenceRef emit(String artifactType, long issuedAtMs, String traceId, Object payloadBody) {
        try {
            // Null-strip the payload ONCE into a tree, and use that same tree for
            // BOTH the content-id computation and the queued record, so the id the
            // worker recomputes over the stored payload matches byte-for-byte.
            JsonNode cleanPayload = evidencePayloadMapper().valueToTree(payloadBody);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("artifact_type", artifactType);
            record.put("issued_at_ms", issuedAtMs);
            if (traceId != null && !traceId.isBlank()) {
                record.put("trace_id", traceId);
            }
            record.put("payload", cleanPayload);

            EvidenceRef ref = null;
            if (identityConfigured()) {
                // Compute the content id over the SAME envelope the worker will
                // build, BEFORE the record is serialized — and never over a
                // response already carrying cycles_evidence (the caller stamps
                // the ref on the response only AFTER this returns), so the
                // attested payload stays free of a self-reference.
                String evidenceId = evidenceIdComputer.compute(
                        artifactType, serverId, signerDid, issuedAtMs, traceId, cleanPayload);
                record.put("evidence_id", evidenceId);
                ref = new EvidenceRef(evidenceId, evidenceUrl(evidenceId));
            }

            repository.push(objectMapper.writeValueAsString(record));
            return ref;
        } catch (Exception e) {
            LOG.error("evidence-source emission failed (artifact_type={}): {}", artifactType, e.getMessage());
            metrics.recordEvidenceEmitFailed(artifactType);
            return null;
        }
    }

    private boolean identityConfigured() {
        return serverId != null && !serverId.isBlank()
                && signerDid != null && !signerDid.isBlank();
    }

    /**
     * {@code {server_id}/evidence/{evidence_id}}. {@code server_id} is already
     * the canonical base INCLUDING the {@code /v1} prefix (e.g.
     * {@code https://cycles.example.com/v1}), so the join adds only
     * {@code /evidence/{id}} and MUST NOT re-add {@code /v1}.
     */
    private String evidenceUrl(String evidenceId) {
        String base = serverId.endsWith("/") ? serverId.substring(0, serverId.length() - 1) : serverId;
        return base + "/evidence/" + evidenceId;
    }

    /**
     * A reference to the CyclesEvidence envelope emitted for an operation: its
     * content-addressed {@code evidence_id} and the absolute {@code cycles_evidence_url}
     * to fetch the signed envelope (via {@code getEvidence}).
     */
    public record EvidenceRef(String evidenceId, String cyclesEvidenceUrl) {
    }
}
