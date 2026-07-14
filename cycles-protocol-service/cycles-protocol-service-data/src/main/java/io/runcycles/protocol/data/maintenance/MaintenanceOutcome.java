package io.runcycles.protocol.data.maintenance;

/** Bounded outcomes for one scheduled-maintenance invocation. */
public enum MaintenanceOutcome {
    SUCCESS("success"),
    FAILED("failed"),
    SKIPPED_LOCKED("skipped_locked"),
    SKIPPED_DISABLED("skipped_disabled"),
    LEASE_ERROR("lease_error"),
    LEASE_LOST("lease_lost");

    private final String tag;

    MaintenanceOutcome(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
