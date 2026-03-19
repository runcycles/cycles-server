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

    public enum DecisionEnum {
        ALLOW, ALLOW_WITH_CAPS, DENY
    }

    public enum CommitOveragePolicy {
        REJECT, ALLOW_IF_AVAILABLE, ALLOW_WITH_OVERDRAFT
    }

    /** YAML schema name: ReservationStatus */
    public enum ReservationStatus {
        ACTIVE, COMMITTED, RELEASED, EXPIRED
    }

    /** CommitResponse.status: spec constrains to enum [COMMITTED] */
    public enum CommitStatus {
        COMMITTED
    }

    /** ReleaseResponse.status: spec constrains to enum [RELEASED] */
    public enum ReleaseStatus {
        RELEASED
    }

    /** EventCreateResponse.status: spec constrains to enum [APPLIED] */
    public enum EventStatus {
        APPLIED
    }

    /** ReservationExtendResponse.status: spec constrains to enum [ACTIVE] */
    public enum ExtendStatus {
        ACTIVE
    }

    public enum ErrorCode {
        INVALID_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND,
        BUDGET_EXCEEDED, BUDGET_FROZEN, BUDGET_CLOSED,
        RESERVATION_EXPIRED, RESERVATION_FINALIZED,
        IDEMPOTENCY_MISMATCH, UNIT_MISMATCH,
        OVERDRAFT_LIMIT_EXCEEDED, DEBT_OUTSTANDING, MAX_EXTENSIONS_EXCEEDED, INTERNAL_ERROR
    }
}
