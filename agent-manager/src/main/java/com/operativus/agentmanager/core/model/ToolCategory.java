package com.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Semantic tool categories surfaced through the {@code GET /api/tools} listing so the
 * UI can group tools by purpose. The backend builds the {@code tool -> category} map
 * at startup by scanning {@code @RequiresCapability} annotations
 * (see {@code ToolController.buildToolCategoryMap}).
 *
 * <p>Note: {@code @RequiresCapability} is metadata-only — it does NOT enforce access
 * at runtime. The categories below are display labels for the picker, not authorization
 * grants. See {@link com.operativus.agentmanager.control.security.RequiresCapability}.</p>
 */
public enum ToolCategory {

    /**
     * Knowledge retrieval, memory, and RAG tools.
     */
    RESEARCHER("Research & Knowledge", "memory_access"),

    /**
     * Web search, URL scraping, and live data tools.
     */
    WEB_AGENT("Web Access", "web_access"),

    /**
     * Financial data access and analysis tools.
     */
    FINANCE("Finance", "finance_access"),

    /**
     * Code execution and sandboxed compute tools.
     */
    DEVELOPER("Development", "code_execution"),

    /**
     * Team orchestration — delegation and handoff tools.
     */
    ORCHESTRATOR("Orchestration", "team_orchestration"),

    /**
     * Destructive or privileged operations requiring approval.
     */
    PRIVILEGED("Privileged Operations", "write_access=destructive"),

    /**
     * Outbound communication and messaging tools — email, chat, SMS, notifications.
     */
    COMMUNICATOR("Communication & Messaging", "notifications"),

    /**
     * Scheduling and background job tools.
     */
    SCHEDULER("Scheduling", "scheduling");

    private final String displayName;
    private final String capabilityPrefix;

    ToolCategory(String displayName, String capabilityPrefix) {
        this.displayName = displayName;
        this.capabilityPrefix = capabilityPrefix;
    }

    public String getDisplayName() { return displayName; }
    public String getCapabilityPrefix() { return capabilityPrefix; }

    @JsonValue
    public String toValue() { return name(); }

    /**
     * Resolves a ToolCategory from a {@code @RequiresCapability} annotation value.
     */
    public static ToolCategory fromCapability(String capability) {
        if (capability == null) return null;
        for (ToolCategory cat : values()) {
            if (capability.startsWith(cat.capabilityPrefix)) {
                return cat;
            }
        }
        return null;
    }
}
