package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EventType {
    // Budget lifecycle (15)
    BUDGET_CREATED("budget.created", EventCategory.BUDGET),
    BUDGET_UPDATED("budget.updated", EventCategory.BUDGET),
    BUDGET_FUNDED("budget.funded", EventCategory.BUDGET),
    BUDGET_DEBITED("budget.debited", EventCategory.BUDGET),
    BUDGET_RESET("budget.reset", EventCategory.BUDGET),
    BUDGET_RESET_SPENT("budget.reset_spent", EventCategory.BUDGET),
    BUDGET_DEBT_REPAID("budget.debt_repaid", EventCategory.BUDGET),
    BUDGET_FROZEN("budget.frozen", EventCategory.BUDGET),
    BUDGET_UNFROZEN("budget.unfrozen", EventCategory.BUDGET),
    BUDGET_CLOSED("budget.closed", EventCategory.BUDGET),
    BUDGET_THRESHOLD_CROSSED("budget.threshold_crossed", EventCategory.BUDGET),
    BUDGET_EXHAUSTED("budget.exhausted", EventCategory.BUDGET),
    BUDGET_OVER_LIMIT_ENTERED("budget.over_limit_entered", EventCategory.BUDGET),
    BUDGET_OVER_LIMIT_EXITED("budget.over_limit_exited", EventCategory.BUDGET),
    BUDGET_DEBT_INCURRED("budget.debt_incurred", EventCategory.BUDGET),
    BUDGET_BURN_RATE_ANOMALY("budget.burn_rate_anomaly", EventCategory.BUDGET),
    // Reservation (5)
    RESERVATION_DENIED("reservation.denied", EventCategory.RESERVATION),
    RESERVATION_DENIAL_RATE_SPIKE("reservation.denial_rate_spike", EventCategory.RESERVATION),
    RESERVATION_EXPIRED("reservation.expired", EventCategory.RESERVATION),
    RESERVATION_EXPIRY_RATE_SPIKE("reservation.expiry_rate_spike", EventCategory.RESERVATION),
    RESERVATION_COMMIT_OVERAGE("reservation.commit_overage", EventCategory.RESERVATION),
    // Tenant (6)
    TENANT_CREATED("tenant.created", EventCategory.TENANT),
    TENANT_UPDATED("tenant.updated", EventCategory.TENANT),
    TENANT_SUSPENDED("tenant.suspended", EventCategory.TENANT),
    TENANT_REACTIVATED("tenant.reactivated", EventCategory.TENANT),
    TENANT_CLOSED("tenant.closed", EventCategory.TENANT),
    TENANT_SETTINGS_CHANGED("tenant.settings_changed", EventCategory.TENANT),
    // API Key (6)
    API_KEY_CREATED("api_key.created", EventCategory.API_KEY),
    API_KEY_REVOKED("api_key.revoked", EventCategory.API_KEY),
    API_KEY_EXPIRED("api_key.expired", EventCategory.API_KEY),
    API_KEY_PERMISSIONS_CHANGED("api_key.permissions_changed", EventCategory.API_KEY),
    API_KEY_AUTH_FAILED("api_key.auth_failed", EventCategory.API_KEY),
    API_KEY_AUTH_FAILURE_RATE_SPIKE("api_key.auth_failure_rate_spike", EventCategory.API_KEY),
    // Policy (3)
    POLICY_CREATED("policy.created", EventCategory.POLICY),
    POLICY_UPDATED("policy.updated", EventCategory.POLICY),
    POLICY_DELETED("policy.deleted", EventCategory.POLICY),
    // System (5)
    SYSTEM_STORE_CONNECTION_LOST("system.store_connection_lost", EventCategory.SYSTEM),
    SYSTEM_STORE_CONNECTION_RESTORED("system.store_connection_restored", EventCategory.SYSTEM),
    SYSTEM_HIGH_LATENCY("system.high_latency", EventCategory.SYSTEM),
    SYSTEM_WEBHOOK_DELIVERY_FAILED("system.webhook_delivery_failed", EventCategory.SYSTEM),
    SYSTEM_WEBHOOK_TEST("system.webhook_test", EventCategory.SYSTEM);

    private final String value;
    private final EventCategory category;

    EventType(String value, EventCategory category) {
        this.value = value;
        this.category = category;
    }

    @JsonValue
    public String getValue() { return value; }

    public EventCategory getCategory() { return category; }

    public boolean isTenantAccessible() {
        return category == EventCategory.BUDGET ||
               category == EventCategory.RESERVATION ||
               category == EventCategory.TENANT;
    }

    public static EventType fromValue(String value) {
        for (EventType t : values()) {
            if (t.value.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown event type: " + value);
    }
}
