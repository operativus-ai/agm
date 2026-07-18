package ai.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Defines standard interruption breakpoints within an Agent execution run
 * that require external asynchronous intervention (e.g., Human-in-the-Loop approvals).
 */
public enum RequiredActionType {
    SWARM_ESCALATION_APPROVAL("SWARM_ESCALATION_APPROVAL"),
    TOOL_APPROVAL("TOOL_APPROVAL"),
    /** REQ-HR follow-up — team orchestrator paused before dispatching a member
     *  whose {@code team_members.human_review} requires confirmation. Resume
     *  via {@code POST /api/v1/approvals/{id}/decide} on the pending row. */
    TEAM_MEMBER_DISPATCH_APPROVAL("TEAM_MEMBER_DISPATCH_APPROVAL");

    private final String actionType;

    RequiredActionType(String actionType) {
        this.actionType = actionType;
    }

    @JsonValue
    public String getActionType() {
        return actionType;
    }

    @JsonCreator
    public static RequiredActionType fromString(String value) {
        if (value == null) return null;
        for (RequiredActionType type : values()) {
            if (type.actionType.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
