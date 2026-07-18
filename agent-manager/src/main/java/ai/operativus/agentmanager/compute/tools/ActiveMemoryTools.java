package ai.operativus.agentmanager.compute.tools;


import org.slf4j.Logger;
import ai.operativus.agentmanager.core.registry.MemoryOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Domain Responsibility: Exposes long-term episodic/semantic memory persistence to LLM Agents as callable functions.
 * State: Stateless
 */
@Configuration
public class ActiveMemoryTools {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(ActiveMemoryTools.class);


    /**
     * @summary Provides a tool for agents to proactively store a semantic fact or preference observed during an interaction.
     * @logic Invokes the MemoryOperations service to persist the provided fact, defaulting to a fallback user ID if omitted from the context.
     */
    @Bean
    @Description("Remember a specific fact, preference, or piece of information for long-term semantic retrieval across sessions.")
    public Function<SaveMemoryRequest, String> saveMemoryTool(MemoryOperations memoryService) {
        return request -> {
            try {
                logger.info("Agent Saving Memory: {}", request.fact());
                String userId = ai.operativus.agentmanager.control.config.SecurityContextUtils.resolveUserId(request.userId());
                logger.debug("Saving memory for userId: {}", userId);
                
                // Note: The memoryService implementation uses AgentContextHolder internally to resolve the current identity
                memoryService.addMemory(request.fact());
                return "Memory saved successfully.";
            } catch (Exception e) {
                logger.error("Failed to save memory", e);
                return "Error saving memory: " + e.getMessage();
            }
        };
    }

    public record SaveMemoryRequest(String fact, String userId) {}
}
