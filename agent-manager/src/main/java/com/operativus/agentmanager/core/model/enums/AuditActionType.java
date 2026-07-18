package com.operativus.agentmanager.core.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AuditActionType {
    CREATE("CREATE"),
    UPDATE("UPDATE"),
    DELETE("DELETE"),
    RESTORE("RESTORE"),
    CLONE("CLONE"),
    IMPORT("IMPORT"),
    ROLLBACK("ROLLBACK");

    private final String value;

    AuditActionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AuditActionType fromValue(String value) {
        if (value == null) return null;
        for (AuditActionType type : values()) {
            if (type.value.equalsIgnoreCase(value)) return type;
        }
        throw new IllegalArgumentException("Unknown audit action type: " + value);
    }
}
