package io.runcycles.protocol.data.exception;

import io.runcycles.protocol.model.Enums;
import lombok.Getter;
import java.util.Map;

/** Cycles Protocol v0.1.23 */
@Getter
public class CyclesProtocolException extends RuntimeException {
    private final Enums.ErrorCode errorCode;
    private final int httpStatus;
    private final Map<String, Object> details;
    
    public CyclesProtocolException(Enums.ErrorCode errorCode, String message, int httpStatus) {
        this(errorCode, message, httpStatus, null);
    }
    
    public CyclesProtocolException(Enums.ErrorCode errorCode, String message, int httpStatus, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.details = details;
    }
    
    public static CyclesProtocolException notFound(String resourceId) {
        return new CyclesProtocolException(Enums.ErrorCode.NOT_FOUND, "Resource not found: " + resourceId, 404);
    }
    
    public static CyclesProtocolException budgetExceeded(String scope) {
        return new CyclesProtocolException(Enums.ErrorCode.BUDGET_EXCEEDED, "Budget exceeded for: " + scope, 409);
    }

    public static CyclesProtocolException overdraftLimitExceeded(String scope) {
        return new CyclesProtocolException(Enums.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED, "Overdraft limit exceeded for: " + scope, 409);
    }

    public static CyclesProtocolException debtOutstanding(String scope) {
        return new CyclesProtocolException(Enums.ErrorCode.DEBT_OUTSTANDING, "Outstanding debt blocks new reservation for: " + scope, 409);
    }
    public static CyclesProtocolException reservationFinalized(String scope) {
        return new CyclesProtocolException(Enums.ErrorCode.RESERVATION_FINALIZED, "Reservation finalized: " + scope, 409);
    }
    public static CyclesProtocolException budgetNotFound(String scope) {
        return new CyclesProtocolException(Enums.ErrorCode.NOT_FOUND, "Budget not found for provided scope: " + scope, 404);
    }
    public static CyclesProtocolException idempotencyMismatch() {
        return new CyclesProtocolException(Enums.ErrorCode.IDEMPOTENCY_MISMATCH, "Provided idempotency does not match the stored one", 409);
    }
    public static CyclesProtocolException unitMismatch() {
        return new CyclesProtocolException(Enums.ErrorCode.UNIT_MISMATCH, "Provided units does not match the stored ones", 400);
    }
    public static CyclesProtocolException reservationExpired() {
        return new CyclesProtocolException(Enums.ErrorCode.RESERVATION_EXPIRED, "Provided reservation has already expired", 410);
    }
    public static CyclesProtocolException reservationExpirationNotFound() {
        return new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR, "Reservation does not have an expiration time", 500);
    }
}
