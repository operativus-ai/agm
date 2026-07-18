package ai.operativus.agentmanager.core.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum JobPriority {
    HIGH("HIGH"),
    NORMAL("NORMAL"),
    LOW("LOW");

    private final String value;

    JobPriority(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static JobPriority fromValue(String value) {
        if (value == null) return null;
        for (JobPriority p : values()) {
            if (p.value.equalsIgnoreCase(value)) return p;
        }
        throw new IllegalArgumentException("Unknown job priority: " + value);
    }
}
