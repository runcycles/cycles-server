package io.runcycles.protocol.api.exception;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Cycles Protocol v0.1.23 - Exception Handler */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CyclesProtocolException.class)
    public ResponseEntity<ErrorResponse> handleCyclesException(CyclesProtocolException ex) {
        LOG.info("Landed in cycles exception handler: clazz={}",ex.getClass());
        return ResponseEntity.status(ex.getHttpStatus()).body(ErrorResponse.builder()
            .error(ex.getErrorCode())
            .message(ex.getMessage())
            .requestId(UUID.randomUUID().toString())
            .details(ex.getDetails())
            .build());
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {

        LOG.info("Landed in generic exception handler: clazz={}",ex.getClass());
        if (ex instanceof CyclesProtocolException){
            return handleCyclesException((CyclesProtocolException) ex);
        }
        else {
            String msg = ex.getMessage() != null ? ":" + ex.getMessage() : "";
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .error(Enums.ErrorCode.INTERNAL_ERROR)
                            .message("Internal error" + msg)
                            .requestId(UUID.randomUUID().toString())
                            .build());
        }
    }
}
