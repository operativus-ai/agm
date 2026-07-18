package com.operativus.agentmanager.core.registry;

/**
 * Domain Responsibility: Registry contract to access MCP Server protocol operations from the Control plane.
 * State: Stateless
 */
public interface McpOperations {

    /**
     * @summary Executes an agent run via MCP protocol.
     * @logic Delegates to underlying agent runner to execute the given message against the agentId.
     */
    String run_agent(String agentId, String message);

    /**
     * @summary Lists all available agents exposed via MCP.
     * @logic Retrieves agent definitions and formats them for MCP consumption.
     */
    String list_agents();
}
