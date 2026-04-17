package io.runcycles.protocol.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Cycles Protocol v0.1.25
 * All enums in one file for convenience
 */
public class Enums {
    public static final String PROTOCOL_VERSION = "0.1.25";

    public enum UnitEnum {
        USD_MICROCENTS, TOKENS, CREDITS, RISK_POINTS
    }

    public enum DecisionEnum {
        ALLOW, ALLOW_WITH_CAPS, DENY
    }

    /**
     * Stable machine-readable reason for decision=DENY on /v1/decide and reserve dry_run.
     * Populated on DecisionResponse.reason_code and ReservationCreateResponse.reason_code.
     * Mirrors the DecisionReasonCode schema in cycles-protocol-v0.yaml (runtime plane).
     *
     * Distinct from ErrorCode: these values appear only on 200 OK responses with decision=DENY.
     * Some labels (e.g. BUDGET_EXCEEDED, BUDGET_NOT_FOUND) overlap conceptually with 4xx error
     * conditions — same underlying budget state, reported two ways depending on the endpoint:
     * /decide and dry_run surface it as a non-4xx DENY decision; non-dry reserve/event surfaces
     * it as a 409/404 error.
     */
    public enum ReasonCode {
        BUDGET_EXCEEDED,
        BUDGET_FROZEN,
        BUDGET_CLOSED,
        BUDGET_NOT_FOUND,
        OVERDRAFT_LIMIT_EXCEEDED,
        DEBT_OUTSTANDING
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

    /**
     * Sort keys accepted by GET /v1/reservations.
     * Spec: cycles-protocol-v0.yaml revision 2026-04-16.
     * Default when sort_by is provided but unset on the wire: CREATED_AT_MS.
     *
     * Wire form is lowercase (reservation_id, created_at_ms, …). @JsonValue
     * emits the wire form on serialization; @JsonCreator fromWire parses
     * case-insensitively and returns null on null input so callers that
     * want 400-on-unknown can convert IllegalArgumentException into a
     * CyclesProtocolException themselves, matching the admin SortDirection
     * pattern.
     */
    public enum ReservationSortBy {
        RESERVATION_ID,
        TENANT,
        SCOPE_PATH,
        STATUS,
        RESERVED,
        CREATED_AT_MS,
        EXPIRES_AT_MS;

        @JsonValue
        public String getWire() {
            return name().toLowerCase();
        }

        @JsonCreator
        public static ReservationSortBy fromWire(String value) {
            if (value == null) return null;
            return ReservationSortBy.valueOf(value.toUpperCase());
        }
    }

    /**
     * Sort direction for list endpoints. Default DESC. Wire form is
     * lowercase ("asc" / "desc"). See ReservationSortBy for the Jackson
     * round-trip contract — same pattern.
     */
    public enum SortDirection {
        ASC, DESC;

        @JsonValue
        public String getWire() {
            return name().toLowerCase();
        }

        @JsonCreator
        public static SortDirection fromWire(String value) {
            if (value == null) return null;
            return SortDirection.valueOf(value.toUpperCase());
        }
    }
}
