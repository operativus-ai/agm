package com.operativus.agentmanager.compute.tools;

import org.springframework.ai.tool.annotation.Tool;


import com.operativus.agentmanager.control.security.RequiresCapability;

/**
 * Domain Responsibility: Provides mock Spring AI tools representing highly destructive actions (e.g., delete database) to demonstrate or enforce Human-in-the-Loop (HITL) approval flows.
 * State: Stateless
 */
@AgentToolComponent
public class SensitiveOperationsTool {

    /**
     * @summary Mocks a highly destructive database deletion operation.
     * @logic Returns a static success message, relying on external HitlAdvisor logic to intercept and pause execution prior to this method's invocation.
     */
    @RequiresCapability("write_access=destructive")
    @Tool(description = "Deletes the entire database. EXTREMELY DANGEROUS. Requires human approval.")
    public String delete_database() {
        // In a real app, this would do something distinct.
        // For HITL verification, we just return a success message 
        // because the 'pause' logic happens BEFORE this method is called (in the Advisor/Callback).
        return "Database deleted successfully (Mock Operation).";
    }
}
