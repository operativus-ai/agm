package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Enumerates the valid roles an agent can hold within a multi-agent team.
 */
public enum NodeRole {
    LEADER,
    MEMBER;

    /**
     * Validates that a string is a known NodeRole value.
     * @throws IllegalArgumentException if the value is not a valid role
     */
    public static NodeRole fromString(String value) {
        if (value == null || value.isBlank()) {
            return MEMBER; // default
        }
        try {
            return NodeRole.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid role '" + value + "'. Valid roles are: LEADER, MEMBER");
        }
    }
}
