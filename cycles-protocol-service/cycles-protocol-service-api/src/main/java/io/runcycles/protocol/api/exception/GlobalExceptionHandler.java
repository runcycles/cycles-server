package io.runcycles.protocol.api.exception;

import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.api.filter.TraceContextFilter;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.EvidenceEmitter;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Cycles Protocol v0.1.25 - Exception Handler */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Budget/lifecycle DENIAL codes that warrant an {@code error} CyclesEvidence
     * envelope (cycles-protocol v0.1.25.5 / cycles-evidence-v0.1). The principle:
     * a code belongs here iff it is a DECISION the server reached while evaluating
     * the request on one of the four core endpoints — the highest-signal evidence
     * an APS receipt binds to. Two families:
     *   - budget denials (reserve/decide/commit): non-dry insufficient budget
     *     surfaces as 409 {@code BUDGET_EXCEEDED} — the canonical wire shape, not
     *     a 200 DENY — plus the frozen/closed/overdraft/debt/unit variants;
     *   - reservation terminal-state denials (commit/release): settling a
     *     reservation that is already finalized ({@code RESERVATION_FINALIZED},
     *     409) or expired ({@code RESERVATION_EXPIRED}, 410). reservation_id is
     *     hoisted for these so evidence-only readers can reconstruct the
     *     authorization -> settlement-denial chain.
     * Pre-evaluation failures (validation, auth, malformed body, not-found,
     * idempotency mismatch) are deliberately excluded — no decision was reached,
     * so there is nothing to attest (matches the spec's `cycles_evidence`
     * "absent for errors raised before evidence could be emitted").
     */
    private static final Set<Enums.ErrorCode> EVIDENCE_DENIAL_CODES = EnumSet.of(
        Enums.ErrorCode.BUDGET_EXCEEDED,
        Enums.ErrorCode.BUDGET_FROZEN,
        Enums.ErrorCode.BUDGET_CLOSED,
        Enums.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED,
        Enums.ErrorCode.DEBT_OUTSTANDING,
        Enums.ErrorCode.UNIT_MISMATCH,
        Enums.ErrorCode.RESERVATION_FINALIZED,
        Enums.ErrorCode.RESERVATION_EXPIRED);

    /** Endpoint patterns (METHOD + best-matching route) that map 1:1 to the
     *  evidence {@code error} payload's {@code endpoint} enum. Denials on any
     *  other route (e.g. extend, GETs) carry no {@code error} evidence. */
    private static final Set<String> EVIDENCE_ENDPOINTS = Set.of(
        "POST /v1/decide",
        "POST /v1/reservations",
        "POST /v1/reservations/{reservation_id}/commit",
        "POST /v1/reservations/{reservation_id}/release");

    /**
     * Request attribute under which the four core controllers stash their parsed
     * {@code @RequestBody} DTO, so this handler can include it as the OPTIONAL
     * {@code request} field of the {@code error} evidence payload (cycles-evidence-v0.1
     * SHOULD include it for a full audit trail). Set after binding succeeds, so it is
     * present for any post-binding denial and absent for pre-binding failures.
     */
    public static final String EVIDENCE_REQUEST_ATTRIBUTE = "io.runcycles.protocol.evidence.request";

    private final EvidenceEmitter evidenceEmitter;

    @Autowired
    public GlobalExceptionHandler(EvidenceEmitter evidenceEmitter) {
        this.evidenceEmitter = evidenceEmitter;
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request != null ? request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) : null;
        return attr != null ? attr.toString() : UUID.randomUUID().toString();
    }

    private String resolveTraceId(HttpServletRequest request) {
        return TraceContextFilter.currentTraceId(request);
    }

    @ExceptionHandler(CyclesProtocolException.class)
    public ResponseEntity<ErrorResponse> handleCyclesException(CyclesProtocolException ex, HttpServletRequest request) {
        LOG.info("Landed in cycles exception handler: clazz={}",ex.getClass());
        ErrorResponse body = ErrorResponse.builder()
            .error(ex.getErrorCode())
            .message(ex.getMessage())
            .requestId(resolveRequestId(request))
            .traceId(resolveTraceId(request))
            .details(ex.getDetails())
            .build();
        maybeEmitErrorEvidence(body, ex, request);
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    /**
     * Emit an {@code error} CyclesEvidence source record over
     * {@code {endpoint, http_status, response}} (the {@code response} AS-IS,
     * before {@code cycles_evidence} is stamped, so the attested payload never
     * references its own id) and stamp the returned ref onto the response — but
     * only for budget/lifecycle denials on the four core endpoints. No-op (no
     * field stamped) for any other error or when the server identity is
     * unconfigured. Fail-open via {@link EvidenceEmitter} — never throws.
     */
    private void maybeEmitErrorEvidence(ErrorResponse body, CyclesProtocolException ex, HttpServletRequest request) {
        if (request == null || !EVIDENCE_DENIAL_CODES.contains(ex.getErrorCode())) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return;
        }
        String endpoint = request.getMethod() + " " + pattern;
        if (!EVIDENCE_ENDPOINTS.contains(endpoint)) {
            return;
        }

        // reservation_id is REQUIRED for the commit/release endpoints (hoisted so
        // evidence-only readers can reconstruct the authorization -> settlement chain).
        // If the path variable is somehow absent (Spring normally sets it before the
        // controller runs), an error payload would be spec-invalid — skip emission
        // rather than emit a malformed envelope.
        boolean reservationScoped = endpoint.endsWith("/commit") || endpoint.endsWith("/release");
        String reservationId = reservationIdFor(request);
        if (reservationScoped && reservationId == null) {
            return;
        }

        Map<String, Object> evidenceBody = new LinkedHashMap<>();
        evidenceBody.put("endpoint", endpoint);
        evidenceBody.put("http_status", ex.getHttpStatus());
        if (reservationId != null) {
            evidenceBody.put("reservation_id", reservationId);
        }
        // request is OPTIONAL but SHOULD be included for a full audit trail (cycles-evidence-v0.1
        // ErrorPayload). The originating controller stashes its parsed @RequestBody DTO as a request
        // attribute; include it when present (absent for pre-binding failures or non-instrumented
        // routes). A redaction policy MAY drop it in a future revision.
        Object requestBody = request.getAttribute(EVIDENCE_REQUEST_ATTRIBUTE);
        if (requestBody != null) {
            evidenceBody.put("request", requestBody);
        }
        evidenceBody.put("response", body);

        EvidenceEmitter.EvidenceRef ref = evidenceEmitter.emit("error",
            System.currentTimeMillis(), resolveTraceId(request), evidenceBody);
        if (ref != null) {
            body.setCyclesEvidence(CyclesEvidenceRef.builder()
                .evidenceId(ref.evidenceId())
                .cyclesEvidenceUrl(ref.cyclesEvidenceUrl())
                .build());
        }
    }

    @SuppressWarnings("unchecked")
    private String reservationIdFor(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map ? ((Map<String, String>) vars).get("reservation_id") : null;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
            .error(Enums.ErrorCode.INVALID_REQUEST)
            .message("Validation failed: " + message)
            .requestId(resolveRequestId(request))
            .traceId(resolveTraceId(request))
            .build());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
            .map(v -> {
                String path = v.getPropertyPath().toString();
                String param = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                return param + ": " + v.getMessage();
            })
            .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
            .error(Enums.ErrorCode.INVALID_REQUEST)
            .message("Validation failed: " + message)
            .requestId(resolveRequestId(request))
            .traceId(resolveTraceId(request))
            .build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
            .error(Enums.ErrorCode.INVALID_REQUEST)
            .message("Malformed request body")
            .requestId(resolveRequestId(request))
            .traceId(resolveTraceId(request))
            .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {

        LOG.error("Unhandled exception: clazz={}", ex.getClass(), ex);
        if (ex instanceof CyclesProtocolException){
            LOG.warn("CyclesProtocolException reached generic handler unexpectedly; check @ControllerAdvice ordering. class={}", ex.getClass().getName());
            return handleCyclesException((CyclesProtocolException) ex, request);
        }
        else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .error(Enums.ErrorCode.INTERNAL_ERROR)
                            .message("Internal error")
                            .requestId(resolveRequestId(request))
                            .traceId(resolveTraceId(request))
                            .build());
        }
    }
}
