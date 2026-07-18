package ai.operativus.agentmanager.core.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RoleType {
    ROLE_VIEWER("ROLE_VIEWER"),
    ROLE_USER("ROLE_USER"),
    ROLE_OPERATOR("ROLE_OPERATOR"),
    ROLE_ADMIN("ROLE_ADMIN"),
    ROLE_SUPER_ADMIN("ROLE_SUPER_ADMIN"),
    ROLE_A2A_AGENT("ROLE_A2A_AGENT"),
    ROLE_MFA_AUTHENTICATED("ROLE_MFA_AUTHENTICATED");

    private final String value;

    RoleType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RoleType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (RoleType type : values()) {
            if (type.name().equalsIgnoreCase(value) || type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown role type: " + value);
    }
}
