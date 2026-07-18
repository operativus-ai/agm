package com.operativus.agentmanager.compute.mcp;

import com.operativus.agentmanager.compute.service.AgentService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.UUID;

import com.operativus.agentmanager.core.registry.McpOperations;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Exposes internal agent coordination methods as Model Context Protocol (MCP) Tools. Fulfills Requirement 5.8 (AgentManager as MCP Server).
 * State: Stateless (Service)
 */
@Service
public class AgentControlService implements McpOperations {

    private final AgentService agentService;
    private final AgentRegistry agentRegistry;

    public AgentControlService(AgentService agentService, AgentRegistry agentRegistry) {
        this.agentService = agentService;
        this.agentRegistry = agentRegistry;
    }

    /**
     * @summary Prompts a specific agent dynamically via MCP.
     * @logic Resolves the target agent by ID and invokes the core AgentService run loop with a new session identifier.
     */
    @Tool(description = "Run a specific agent with a message.")
    public String run_agent(
            @ToolParam(description = " The ID of the agent to run") String agentId,
            @ToolParam(description = "The message to send") String message) {
        
        return agentService.run(agentId, message, UUID.randomUUID().toString()).content();
    }
    
    /**
     * @summary Lists all active agents capable of invocation via MCP.
     * @logic Dynamically queries the AgentRegistry for all available execution definitions.
     */
    @Tool(description = "List all available agents.")
    public String list_agents() {
        String available = agentRegistry.findAll(false,
                com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId()).stream()
                .map(com.operativus.agentmanager.core.model.definitions.AgentDefinition::id)
                .collect(Collectors.joining(", "));
        return available.isEmpty() ? "No agents presently active in the registry." : available;
    }
}
