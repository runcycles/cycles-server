package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.EvidenceQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits CyclesEvidence SOURCE records — the operational facts a signed
 * {@code cycles-evidence/v0.1} envelope wraps — onto a dedicated Redis queue
 * for the event-tier worker to canonicalize, sign (with the server's Ed25519
 * identity), store and serve.
 *
 * <p>This server stamps ONLY operational facts: {@code artifact_type},
 * {@code issued_at_ms}, {@code trace_id}, and the artifact-specific payload
 * body. The server identity ({@code server_id} / {@code signer_did}) is added
 * by the signing worker, co-located with the key.
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

    /**
     * Synchronously enqueue an evidence-source record for a lifecycle artifact.
     * Call AFTER the ledger write commits and BEFORE returning the response.
     *
     * @param artifactType decide / reserve / commit / release / error
     * @param issuedAtMs   the event's issuance clock (epoch millis), stamped at
     *                     RESPONSE time — the authoritative event time
     * @param traceId      correlation id, or {@code null}/blank to omit
     * @param payloadBody  the artifact-specific body — becomes
     *                     {@code payload.<artifactType>} (see class javadoc)
     */
    public void emit(String artifactType, long issuedAtMs, String traceId, Object payloadBody) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("artifact_type", artifactType);
            record.put("issued_at_ms", issuedAtMs);
            if (traceId != null && !traceId.isBlank()) {
                record.put("trace_id", traceId);
            }
            record.put("payload", payloadBody);

            repository.push(objectMapper.writeValueAsString(record));
        } catch (Exception e) {
            LOG.error("evidence-source emission failed (artifact_type={}): {}", artifactType, e.getMessage());
            metrics.recordEvidenceEmitFailed(artifactType);
        }
    }
}
