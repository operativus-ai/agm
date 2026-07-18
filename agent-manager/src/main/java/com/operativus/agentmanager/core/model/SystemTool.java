package com.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Defines standard multi-agent orchestration and foundational capabilities tightly bound
 * to the Procurator infrastructure. Provides type-safe identifiers for system-level tool bindings.
 */
public enum SystemTool {
    DELEGATE_TO_AGENT("delegate_to_agent"),
    HAND_OFF_TO_AGENT("hand_off_to_agent"),
    SEARCH_KNOWLEDGE_BASE("search_knowledge_base"),
    /** REQ-TT-3/7 — TaskManagementTool methods, auto-injected for TASKS-mode coordinators. */
    TASK_CREATE("createTask"),
    TASK_UPDATE_STATUS("updateTaskStatus"),
    TASK_QUERY("queryTasks"),
    TASK_GET("getTask");

    private final String toolName;

    SystemTool(String toolName) {
        this.toolName = toolName;
    }

    @JsonValue
    public String getToolName() {
        return toolName;
    }

    @JsonCreator
    public static SystemTool fromString(String value) {
        if (value == null) return null;
        for (SystemTool tool : values()) {
            if (tool.toolName.equalsIgnoreCase(value) || tool.name().equalsIgnoreCase(value)) {
                return tool;
            }
        }
        return null;
    }
}
