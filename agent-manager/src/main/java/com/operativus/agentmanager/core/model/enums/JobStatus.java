package com.operativus.agentmanager.core.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum JobStatus {
    QUEUED("QUEUED"),
    PROCESSING("PROCESSING"),
    PAUSED("PAUSED"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    DLQ("DLQ"),
    CANCELLED("CANCELLED");

    private final String value;

    JobStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static JobStatus fromValue(String value) {
        if (value == null) return null;
        for (JobStatus s : values()) {
            if (s.value.equalsIgnoreCase(value)) return s;
        }
        throw new IllegalArgumentException("Unknown job status: " + value);
    }
}
