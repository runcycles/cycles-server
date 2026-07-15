package io.runcycles.protocol.api.exception;

import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.api.filter.TraceContextFilter;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.EvidenceEmitter;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import jakarta.validation.ConstraintViolationException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;
    private EvidenceEmitter evidenceEmitter;

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @BeforeEach
    void setUp() {
        evidenceEmitter = mock(EvidenceEmitter.class);
        handler = new GlobalExceptionHandler(evidenceEmitter);
        request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-test-123");
        request.setAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE, TRACE_ID);
    }

    /** Mark the request as having matched a given route, as Spring's handler
     *  mapping does before the controller runs — required for error evidence. */
    private void withRoute(String method, String pattern, Map<String, String> uriVars) {
        request.setMethod(method);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        if (uriVars != null) {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriVars);
        }
    }

    @Test
    void shouldHandleCyclesProtocolException() {
        CyclesProtocolException ex = CyclesProtocolException.budgetExceeded("tenant:acme");

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.BUDGET_EXCEEDED);
        assertThat(response.getBody().getRequestId()).isEqualTo("req-test-123");
        assertThat(response.getBody().getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void tenantClosedMapsTo409WithStandardErrorShape() {
        // Governance Rule 2 terminal-owner guard: 409 TENANT_CLOSED must ride
        // the same ErrorResponse envelope as every other 409 (error/message/
        // request_id/trace_id). (No route matched here, so no error evidence —
        // emission per endpoint is covered below.)
        CyclesProtocolException ex = CyclesProtocolException.tenantClosed("acme");

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.TENANT_CLOSED);
        assertThat(response.getBody().getMessage()).contains("acme").contains("closed");
        assertThat(response.getBody().getRequestId()).isEqualTo("req-test-123");
        assertThat(response.getBody().getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("TENANT_CLOSED denial on create emits error evidence (governance sibling of BUDGET_CLOSED)")
    void tenantClosedDenialOnCreateEmitsErrorEvidence() {
        // TENANT_CLOSED is IN EVIDENCE_DENIAL_CODES (evidence ErrorResponseMirror,
        // cycles-evidence-v0.2.yaml 0.2.1, runcycles/cycles-protocol#125): the
        // owner-level sibling of the ledger-level BUDGET_CLOSED — a decision was
        // reached and denied, so the signed denial receipt is emitted.
        withRoute("POST", "/v1/reservations", null);
        String evId = "d".repeat(64);
        when(evidenceEmitter.emit(eq("error"), anyLong(), eq(TRACE_ID), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef(evId,
                        "https://cycles.example.com/v1/evidence/" + evId));

        ResponseEntity<ErrorResponse> response =
                handler.handleCyclesException(CyclesProtocolException.tenantClosed("acme"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCyclesEvidence()).isNotNull();
        assertThat(response.getBody().getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), eq(TRACE_ID), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .containsEntry("endpoint", "POST /v1/reservations")
                .containsEntry("http_status", 409)
                .doesNotContainKey("reservation_id")
                .containsKey("response");
    }

    @Test
    @DisplayName("TENANT_CLOSED denial on commit/release hoists reservation_id into the evidence payload")
    void tenantClosedDenialOnCommitAndReleaseHoistsReservationId() {
        withRoute("POST", "/v1/reservations/{reservation_id}/commit",
                Map.of("reservation_id", "res_tc1"));
        when(evidenceEmitter.emit(eq("error"), anyLong(), anyString(), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef("e".repeat(64), "u"));

        handler.handleCyclesException(CyclesProtocolException.tenantClosed("acme"), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> commitCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), anyString(), commitCaptor.capture());
        assertThat(commitCaptor.getValue())
                .containsEntry("endpoint", "POST /v1/reservations/{reservation_id}/commit")
                .containsEntry("reservation_id", "res_tc1");

        withRoute("POST", "/v1/reservations/{reservation_id}/release",
                Map.of("reservation_id", "res_tc2"));

        handler.handleCyclesException(CyclesProtocolException.tenantClosed("acme"), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> releaseCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter, org.mockito.Mockito.times(2))
                .emit(eq("error"), anyLong(), anyString(), releaseCaptor.capture());
        assertThat(releaseCaptor.getValue())
                .containsEntry("endpoint", "POST /v1/reservations/{reservation_id}/release")
                .containsEntry("reservation_id", "res_tc2");
    }

    @Test
    @DisplayName("TENANT_CLOSED on the extend route emits nothing (extend is not an evidence endpoint)")
    void tenantClosedOnExtendRouteDoesNotEmit() {
        // EVIDENCE_ENDPOINTS covers decide/create/commit/release but NOT extend —
        // TENANT_CLOSED on extend carries no error evidence, same as every other
        // denial code there. Deliberate; pinned here.
        withRoute("POST", "/v1/reservations/{reservation_id}/extend",
                Map.of("reservation_id", "res_tc3"));

        ResponseEntity<ErrorResponse> response =
                handler.handleCyclesException(CyclesProtocolException.tenantClosed("acme"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCyclesEvidence()).isNull();
        verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("handled CyclesProtocolException log includes operator context")
    void cyclesExceptionLogIncludesOperatorContext(CapturedOutput output) {
        request.setRequestURI("/v1/reservations/res_abc123/commit");
        withRoute("POST", "/v1/reservations/{reservation_id}/commit",
                Map.of("reservation_id", "res_abc123"));

        handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        assertThat(output).contains("Cycles protocol exception handled")
                .contains("method=POST")
                .contains("path=/v1/reservations/res_abc123/commit")
                .contains("route=/v1/reservations/{reservation_id}/commit")
                .contains("status=409")
                .contains("error=BUDGET_EXCEEDED")
                .contains("request_id=req-test-123")
                .contains("trace_id=" + TRACE_ID)
                .contains("reservation_id=res_abc123");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("handled CyclesProtocolException log flattens CR/LF in request-derived fields")
    void cyclesExceptionLogSanitizesRequestDerivedFields(CapturedOutput output) {
        request.setRequestURI("/v1/reservations/res_abc123\r\nforged=true/commit");
        withRoute("POST", "/v1/reservations/{reservation_id}/commit",
                Map.of("reservation_id", "res_abc123\r\nforged=true"));

        handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        assertThat(output)
                .contains("path=/v1/reservations/res_abc123  forged=true/commit")
                .contains("reservation_id=res_abc123  forged=true")
                .doesNotContain("reservation_id=res_abc123\r\nforged=true");
    }

    @Test
    @DisplayName("trace_id propagates into ErrorResponse across all handler paths")
    void traceIdPropagatesAcrossHandlers() throws Exception {
        // CyclesProtocolException
        assertThat(handler.handleCyclesException(CyclesProtocolException.notFound("x"), request)
                .getBody().getTraceId()).isEqualTo(TRACE_ID);
        // MalformedJson
        assertThat(handler.handleMessageNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad"), request)
                .getBody().getTraceId()).isEqualTo(TRACE_ID);
        // Generic 500
        assertThat(handler.handleGenericException(new RuntimeException("boom"), request)
                .getBody().getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("trace_id is null when attribute is not set (filter didn't run)")
    void traceIdNullWhenAttributeAbsent() {
        MockHttpServletRequest bare = new MockHttpServletRequest();
        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(
                CyclesProtocolException.notFound("x"), bare);
        assertThat(response.getBody().getTraceId()).isNull();
    }

    @Test
    void shouldHandleCyclesProtocolExceptionWithDetails() {
        Map<String, Object> details = Map.of("scope", "tenant:acme", "remaining", 0);
        CyclesProtocolException ex = new CyclesProtocolException(
                Enums.ErrorCode.BUDGET_EXCEEDED, "Budget exceeded", 409, details);

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(ex, request);

        assertThat(response.getBody().getDetails()).containsEntry("scope", "tenant:acme");
    }

    @Test
    void shouldHandleGenericExceptionAs500() {
        RuntimeException ex = new RuntimeException("something broke");

        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.INTERNAL_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("Internal error");
    }

    @Test
    void shouldHandleCyclesExceptionInGenericHandler() {
        // If a CyclesProtocolException reaches the generic handler, it should still be handled correctly
        CyclesProtocolException ex = CyclesProtocolException.notFound("res_123");

        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldIncludeRequestIdInResponse() {
        CyclesProtocolException ex = CyclesProtocolException.notFound("res_123");

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(ex, request);

        assertThat(response.getBody().getRequestId()).isEqualTo("req-test-123");
    }

    @Test
    void shouldGenerateRequestIdWhenMissing() {
        MockHttpServletRequest requestNoId = new MockHttpServletRequest();
        // No REQUEST_ID_ATTRIBUTE set
        CyclesProtocolException ex = CyclesProtocolException.notFound("res_123");

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(ex, requestNoId);

        // Should generate a UUID fallback
        assertThat(response.getBody().getRequestId()).isNotBlank();
    }

    @Test
    void shouldHandleValidationException() throws Exception {
        org.springframework.validation.BeanPropertyBindingResult bindingResult =
            new org.springframework.validation.BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new org.springframework.validation.FieldError("request", "amount", "must not be null"));
        MethodParameter param = new MethodParameter(
                Object.class.getDeclaredMethod("toString"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).contains("amount");
        assertThat(response.getBody().getMessage()).startsWith("Validation failed:");
        assertThat(response.getBody().getRequestId()).isEqualTo("req-test-123");
    }

    @Test
    void shouldHandleConstraintViolationWithDottedPath() {
        jakarta.validation.ConstraintViolation<?> violation = org.mockito.Mockito.mock(jakarta.validation.ConstraintViolation.class);
        jakarta.validation.Path path = org.mockito.Mockito.mock(jakarta.validation.Path.class);
        org.mockito.Mockito.when(path.toString()).thenReturn("list.limit");
        org.mockito.Mockito.when(violation.getPropertyPath()).thenReturn(path);
        org.mockito.Mockito.when(violation.getMessage()).thenReturn("must be greater than 0");

        ConstraintViolationException ex = new ConstraintViolationException(java.util.Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).contains("limit");
        assertThat(response.getBody().getMessage()).contains("must be greater than 0");
        assertThat(response.getBody().getRequestId()).isEqualTo("req-test-123");
    }

    @Test
    void shouldHandleConstraintViolationWithSimplePath() {
        jakarta.validation.ConstraintViolation<?> violation = org.mockito.Mockito.mock(jakarta.validation.ConstraintViolation.class);
        jakarta.validation.Path path = org.mockito.Mockito.mock(jakarta.validation.Path.class);
        org.mockito.Mockito.when(path.toString()).thenReturn("amount");
        org.mockito.Mockito.when(violation.getPropertyPath()).thenReturn(path);
        org.mockito.Mockito.when(violation.getMessage()).thenReturn("must not be null");

        ConstraintViolationException ex = new ConstraintViolationException(java.util.Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).contains("amount");
        assertThat(response.getBody().getMessage()).doesNotContain(".");
        assertThat(response.getBody().getRequestId()).isEqualTo("req-test-123");
    }

    @Test
    void shouldHandleMalformedJson() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("bad json");

        ResponseEntity<ErrorResponse> response = handler.handleMessageNotReadable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
    }

    @Test
    void shouldHandleNullRequest() {
        CyclesProtocolException ex = CyclesProtocolException.notFound("res_123");

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(ex, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        // Should generate a UUID fallback when request is null
        assertThat(response.getBody().getRequestId()).isNotBlank();
    }

    // ---- error CyclesEvidence (v0.1.25.5) ----

    @Test
    @DisplayName("non-dry reserve denial emits `error` evidence and stamps cycles_evidence")
    void reserveDenialEmitsAndStampsErrorEvidence() {
        withRoute("POST", "/v1/reservations", null);
        String evId = "a".repeat(64);
        when(evidenceEmitter.emit(eq("error"), anyLong(), eq(TRACE_ID), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef(evId,
                        "https://cycles.example.com/v1/evidence/" + evId));

        ResponseEntity<ErrorResponse> response =
                handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCyclesEvidence()).isNotNull();
        assertThat(response.getBody().getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
        // Evidence is emitted over endpoint + http_status + response (no reservation_id on reserve).
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), eq(TRACE_ID), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .containsEntry("endpoint", "POST /v1/reservations")
                .containsEntry("http_status", 409)
                .doesNotContainKey("reservation_id")
                .containsKey("response");
    }

    @Test
    @DisplayName("commit denial hoists reservation_id into the error evidence payload")
    void commitDenialHoistsReservationId() {
        withRoute("POST", "/v1/reservations/{reservation_id}/commit",
                Map.of("reservation_id", "res_abc123"));
        when(evidenceEmitter.emit(eq("error"), anyLong(), anyString(), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef("b".repeat(64), "u"));

        handler.handleCyclesException(
                CyclesProtocolException.overdraftLimitExceeded("tenant:acme"), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .containsEntry("endpoint", "POST /v1/reservations/{reservation_id}/commit")
                .containsEntry("reservation_id", "res_abc123");
    }

    @Test
    @DisplayName("emitter unconfigured (null ref) leaves cycles_evidence absent but still returns the error")
    void denialWithUnconfiguredEmitterOmitsRef() {
        withRoute("POST", "/v1/reservations", null);
        when(evidenceEmitter.emit(eq("error"), anyLong(), any(), any())).thenReturn(null);

        ResponseEntity<ErrorResponse> response =
                handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCyclesEvidence()).isNull();
    }

    @Test
    @DisplayName("pre-evaluation errors (validation/auth/not-found) emit no error evidence")
    void preEvaluationErrorsDoNotEmit() {
        withRoute("POST", "/v1/reservations", null);

        handler.handleCyclesException(CyclesProtocolException.notFound("res_x"), request);

        verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("budget denial on a non-evidence route (no matched pattern) emits nothing")
    void denialWithoutMatchedRouteDoesNotEmit() {
        // No BEST_MATCHING_PATTERN attribute set (e.g. error raised before routing).
        request.setMethod("POST");

        ResponseEntity<ErrorResponse> response =
                handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        assertThat(response.getBody().getCyclesEvidence()).isNull();
        verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("budget denial on the extend route (not a core evidence endpoint) emits nothing")
    void denialOnExtendRouteDoesNotEmit() {
        withRoute("POST", "/v1/reservations/{reservation_id}/extend",
                Map.of("reservation_id", "res_abc123"));

        handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("commit denial on an already-finalized reservation emits error evidence with reservation_id")
    void commitFinalizedDenialEmitsWithReservationId() {
        withRoute("POST", "/v1/reservations/{reservation_id}/commit",
                Map.of("reservation_id", "res_fin"));
        when(evidenceEmitter.emit(eq("error"), anyLong(), anyString(), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef("c".repeat(64), "u"));

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(
                CyclesProtocolException.reservationFinalized("res_fin"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCyclesEvidence()).isNotNull();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .containsEntry("endpoint", "POST /v1/reservations/{reservation_id}/commit")
                .containsEntry("http_status", 409)
                .containsEntry("reservation_id", "res_fin");
    }

    @Test
    @DisplayName("release denial on an expired reservation (410) emits error evidence with reservation_id")
    void releaseExpiredDenialEmitsWithReservationId() {
        withRoute("POST", "/v1/reservations/{reservation_id}/release",
                Map.of("reservation_id", "res_exp"));
        when(evidenceEmitter.emit(eq("error"), anyLong(), anyString(), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef("d".repeat(64), "u"));

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(
                CyclesProtocolException.reservationExpired(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(response.getBody().getCyclesEvidence()).isNotNull();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .containsEntry("endpoint", "POST /v1/reservations/{reservation_id}/release")
                .containsEntry("http_status", 410)
                .containsEntry("reservation_id", "res_exp");
    }

    @Test
    @DisplayName("when the controller stashed the request DTO, it is included in the error evidence payload")
    void reserveDenialIncludesStashedRequestBody() {
        withRoute("POST", "/v1/reservations", null);
        Object requestDto = Map.of("idempotency_key", "k1", "estimate", Map.of("unit", "TOKENS", "amount", 100));
        request.setAttribute(GlobalExceptionHandler.EVIDENCE_REQUEST_ATTRIBUTE, requestDto);
        when(evidenceEmitter.emit(eq("error"), anyLong(), any(), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef("a".repeat(64), "u"));

        handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), any(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).containsEntry("request", requestDto);
    }

    @Test
    @DisplayName("absent request DTO (e.g. pre-binding) omits request from the error evidence payload")
    void reserveDenialOmitsRequestWhenNotStashed() {
        withRoute("POST", "/v1/reservations", null);
        when(evidenceEmitter.emit(eq("error"), anyLong(), any(), any()))
                .thenReturn(new EvidenceEmitter.EvidenceRef("a".repeat(64), "u"));

        handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceEmitter).emit(eq("error"), anyLong(), any(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).doesNotContainKey("request");
    }

    @Test
    @DisplayName("commit/release denial without a reservation_id path var skips emission (would be spec-invalid)")
    void commitDenialWithoutReservationIdDoesNotEmit() {
        // matched the commit route but the URI template var is absent (defensive edge)
        withRoute("POST", "/v1/reservations/{reservation_id}/commit", null);

        ResponseEntity<ErrorResponse> response =
                handler.handleCyclesException(CyclesProtocolException.budgetExceeded("tenant:acme"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCyclesEvidence()).isNull();
        verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
    }

    @Test
    void requestErrorAndBothGenericBranchesHandleNullServletRequest() {
        assertThat(handler.handleMessageNotReadable(null, null).getStatusCode().value()).isEqualTo(400);
        assertThat(handler.handleGenericException(CyclesProtocolException.notFound("x"), null)
            .getStatusCode().value()).isEqualTo(404);
        assertThat(handler.handleGenericException(new RuntimeException("boom"), null)
            .getStatusCode().value()).isEqualTo(500);
    }
}
