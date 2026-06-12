package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.EvidenceQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
 * <p>Fire-and-forget on a dedicated executor — emission never blocks or fails
 * the lifecycle response (mirrors {@link EventEmitterService}).
 */
@Service
public class EvidenceEmitter implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceEmitter.class);

    private final ExecutorService executor = Executors.newFixedThreadPool(
            2, r -> {
                Thread t = new Thread(r, "evidence-emit");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    private EvidenceQueueRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Queue an evidence-source record for a lifecycle artifact.
     *
     * @param artifactType decide / reserve / commit / release / error
     * @param issuedAtMs   the event's issuance clock (epoch millis). Stamped at
     *                     RESPONSE time — the authoritative event time — not at
     *                     async worker sign-time, matching the reference fixtures.
     * @param traceId      correlation id, or {@code null}/blank to omit
     * @param payloadBody  the artifact-specific body — becomes
     *                     {@code payload.<artifactType>} (see class javadoc for
     *                     the per-artifact shape)
     */
    public void emit(String artifactType, long issuedAtMs, String traceId, Object payloadBody) {
        try {
            executor.execute(() -> {
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
                    LOG.error("Failed to emit evidence-source record (artifact_type={}): {}",
                            artifactType, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.error("Evidence-source emission rejected (artifact_type={}): {}", artifactType, e.getMessage());
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
