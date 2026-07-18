package com.operativus.agentmanager.core.registry;
import org.springframework.ai.tool.ToolCallback;
import java.util.Map;

/**
 * Domain Responsibility: Central registry for globally available Agent Tools and functional callbacks.
 * State: Stateless
 */
public interface ToolRegistry {

    /**
     * @summary Retrieves a map of all registered tools ready for execution.
     * @logic Returns an immutable mapping of tool names to their respective Spring AI ToolCallback functions.
     */
    Map<String, ToolCallback> getAvailableTools();
}
