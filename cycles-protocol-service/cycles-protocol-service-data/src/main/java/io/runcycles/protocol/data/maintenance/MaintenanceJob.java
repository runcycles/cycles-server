package io.runcycles.protocol.data.maintenance;

/** Fixed scheduled-maintenance identities used for Redis leases and metrics. */
public enum MaintenanceJob {
    RESERVATION_EXPIRY("reservation_expiry"),
    AUDIT_RETENTION("audit_retention"),
    EVENT_RETENTION("event_retention"),
    CREATED_AT_REPAIR("created_at_repair"),
    CREATED_AT_SWEEP("created_at_sweep");

    private final String tag;

    MaintenanceJob(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
