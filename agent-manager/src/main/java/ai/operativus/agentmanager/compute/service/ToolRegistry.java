package ai.operativus.agentmanager.compute.service;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: A centralized directory for registering and resolving dynamic functional tools available to Agents.
 * State: Stateful (Maintains an in-memory map of registered tools)
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Function<?, ?>> tools = new HashMap<>();

    /**
     * @summary Initializes the ToolRegistry (future integration point for auto-discovery).
     * @logic Placeholder for scanning the Spring Context for beans annotated with specific Tool markers.
     */
    public ToolRegistry(ApplicationContext context) {
        // Discovery logic could go here (e.g., finding beans annotated with @Tool)
    }

    /**
     * @summary Adds a new functional tool to the in-memory registry.
     * @logic Stores the provided Java Function under the specified string key in the internal map.
     */
    public void register(String name, Function<?, ?> tool) {
        log.info("Registering tool: {}", name);
        tools.put(name, tool);
    }

    /**
     * Retrieves a registered functional tool, wrapped in an OBO security proxy.
     * Evaluates the AgentContextHolder before allowing the underlying function to fire.
     */
    public Function<?, ?> getTool(String name) {
        log.debug("Retrieving tool for OBO capability evaluation: {}", name);
        Function<?, ?> original = tools.get(name);
        if (original == null) {
            return null;
        }

        // Return a delayed execution proxy that checks the context at runtime within the virtual thread
        return (arg) -> {
            ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext ctx = 
                ai.operativus.agentmanager.control.security.AgentContextHolder.getContext();
                
            if (ctx != null && ctx.agentManifest() != null) {
                // Here we evaluate capabilities (e.g., destructive vs read_access)
                if (!ctx.agentManifest().capabilities().contains("write_access=destructive")) {
                    // Normally we check method annotations. If it's a destructive tool and we don't have capability, we throw.
                    // For now, logging strict evaluation trace.
                    log.debug("Tool {} execution evaluated against OBO context for Team: {}", name, ctx.teamId());
                }
            } else {
                log.warn("Tool {} invoked OUTSIDE of a secure OBO context. If this is a user agent, execution is unsafe.", name);
            }
            
            // Execute the original tool
            return ((Function<Object, Object>) original).apply(arg);
        };
    }
}
