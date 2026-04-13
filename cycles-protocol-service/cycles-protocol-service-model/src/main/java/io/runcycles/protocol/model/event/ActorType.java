package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActorType {
    ADMIN("admin"),
    API_KEY("api_key"),
    // v0.1.25.8 (cycles-protocol revision 2026-04-13): admin operator
    // performing a write (currently only releaseReservation in the
    // runtime plane) on behalf of a tenant via the new dual-auth
    // allowance. Distinct from ADMIN (system-level) so security review
    // can tell admin-driven tenant-resource actions apart from tenant
    // self-service. Mirrors the same value the governance-admin spec
    // emits for createBudget / createPolicy / updatePolicy.
    ADMIN_ON_BEHALF_OF("admin_on_behalf_of"),
    SYSTEM("system"),
    SCHEDULER("scheduler");

    private final String value;

    ActorType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ActorType fromValue(String value) {
        for (ActorType t : values()) {
            if (t.value.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown actor type: " + value);
    }
}
