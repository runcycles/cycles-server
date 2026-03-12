package io.runcycles.protocol.api.exception;

import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.stream.Collectors;

/** Cycles Protocol v0.1.23 - Exception Handler */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request != null ? request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) : null;
        return attr != null ? attr.toString() : UUID.randomUUID().toString();
    }

    @ExceptionHandler(CyclesProtocolException.class)
    public ResponseEntity<ErrorResponse> handleCyclesException(CyclesProtocolException ex, HttpServletRequest request) {
        LOG.info("Landed in cycles exception handler: clazz={}",ex.getClass());
        return ResponseEntity.status(ex.getHttpStatus()).body(ErrorResponse.builder()
            .error(ex.getErrorCode())
            .message(ex.getMessage())
            .requestId(resolveRequestId(request))
            .details(ex.getDetails())
            .build());
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
            .build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
            .error(Enums.ErrorCode.INVALID_REQUEST)
            .message("Malformed request body")
            .requestId(resolveRequestId(request))
            .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {

        LOG.error("Unhandled exception: clazz={}", ex.getClass(), ex);
        if (ex instanceof CyclesProtocolException){
            return handleCyclesException((CyclesProtocolException) ex, request);
        }
        else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .error(Enums.ErrorCode.INTERNAL_ERROR)
                            .message("Internal error")
                            .requestId(resolveRequestId(request))
                            .build());
        }
    }
}
