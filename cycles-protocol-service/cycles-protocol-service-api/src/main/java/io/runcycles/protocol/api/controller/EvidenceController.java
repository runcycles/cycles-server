package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.api.filter.TraceContextFilter;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.EvidenceStoreReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Public retrieval of signed CyclesEvidence envelopes by content id
 * (cycles-protocol-v0 {@code getEvidence}).
 *
 * <p>No auth — see {@code SecurityConfig.PUBLIC_PATHS} and the spec's
 * {@code getEvidence} description for the capability-URL rationale: the
 * {@code evidence_id} is an unguessable content-hash capability and the
 * envelope is content-addressed and signed. The envelope is served verbatim
 * (bytes, not re-encoded) and immutably cacheable.
 */
@RestController
@RequestMapping("/v1/evidence")
@Tag(name = "Evidence")
@Validated
public class EvidenceController {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceController.class);

    private final EvidenceStoreReader store;

    public EvidenceController(EvidenceStoreReader store) {
        this.store = store;
    }

    @GetMapping("/{evidence_id}")
    @Operation(operationId = "getEvidence",
            summary = "Fetch a signed CyclesEvidence envelope by content id")
    public ResponseEntity<byte[]> getEvidence(
            @PathVariable("evidence_id")
            @Pattern(regexp = "^[0-9a-f]{64}$",
                    message = "evidence_id must be 64 lowercase hex characters")
            String evidenceId,
            HttpServletRequest request) {
        LOG.info("GET /v1/evidence/{evidence_id} evidence_id={} request_id={} trace_id={}",
                evidenceId, resolveRequestId(request), TraceContextFilter.currentTraceId(request));
        String envelope = store.get(evidenceId);
        if (envelope == null) {
            throw CyclesProtocolException.notFound(evidenceId);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(envelope.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request != null ? request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) : null;
        return attr != null ? attr.toString() : null;
    }
}
