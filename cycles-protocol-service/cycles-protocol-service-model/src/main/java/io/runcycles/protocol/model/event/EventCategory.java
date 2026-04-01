package io.runcycles.protocol.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EventCategory {
    BUDGET("budget"),
    TENANT("tenant"),
    API_KEY("api_key"),
    POLICY("policy"),
    RESERVATION("reservation"),
    SYSTEM("system");

    private final String value;

    EventCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EventCategory fromValue(String value) {
        for (EventCategory c : values()) {
            if (c.value.equals(value)) return c;
        }
        throw new IllegalArgumentException("Unknown event category: " + value);
    }
}
