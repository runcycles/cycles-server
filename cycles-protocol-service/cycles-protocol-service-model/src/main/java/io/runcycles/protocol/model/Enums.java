package io.runcycles.protocol.model;

/**
 * Cycles Protocol v0.1.23
 * All enums in one file for convenience
 */
public class Enums {
    public static final String PROTOCOL_VERSION = "0.1.23";
    
    public enum UnitEnum {
        USD_MICROCENTS, TOKENS, CREDITS, RISK_POINTS
    }
    
    public enum CommitOveragePolicy {
        REJECT, ALLOW_IF_AVAILABLE, ALLOW_WITH_OVERDRAFT
    }
    
    public enum ReservationState {
        RESERVED, COMMITTED, RELEASED, EXPIRED
    }
    
    public enum ErrorCode {
        INVALID_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND,
        BUDGET_EXCEEDED, RESERVATION_EXPIRED, RESERVATION_FINALIZED,BUDGET_NOT_FOUND,
        IDEMPOTENCY_MISMATCH, UNIT_MISMATCH,
        OVERDRAFT_LIMIT_EXCEEDED, DEBT_OUTSTANDING, INTERNAL_ERROR
    }
}
