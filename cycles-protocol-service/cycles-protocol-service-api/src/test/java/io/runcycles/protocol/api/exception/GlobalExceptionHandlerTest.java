package io.runcycles.protocol.api.exception;

import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-test-123");
    }

    @Test
    void shouldHandleCyclesProtocolException() {
        CyclesProtocolException ex = CyclesProtocolException.budgetExceeded("tenant:acme");

        ResponseEntity<ErrorResponse> response = handler.handleCyclesException(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.BUDGET_EXCEEDED);
        assertThat(response.getBody().getRequestId()).isEqualTo("req-test-123");
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
    void shouldHandleMalformedJson() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("bad json");

        ResponseEntity<ErrorResponse> response = handler.handleMessageNotReadable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
    }
}
