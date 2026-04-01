package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActorType {
    ADMIN("admin"),
    API_KEY("api_key"),
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
