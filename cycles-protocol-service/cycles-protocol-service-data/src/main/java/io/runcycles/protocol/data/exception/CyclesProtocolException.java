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
        return new CyclesProtocolException(Enums.ErrorCode.RESERVATION_FINALIZED, "Reservation finalized: " + scope, 400);
    }
    public static CyclesProtocolException budgetNotFound(String scope) {
        return new CyclesProtocolException(Enums.ErrorCode.BUDGET_NOT_FOUND, "Budget not found for provided scope: " + scope, 400);
    }
}
