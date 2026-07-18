package ai.operativus.agentmanager.compute.config;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Domain Responsibility: Bridges the Procurator ScopedValue-based execution context into SLF4J MDC (Mapped Diagnostic Context)
 * so that all downstream log statements automatically include agentic identity fields (runId, agentId, sessionId, etc.).
 * This filter runs on every HTTP request and ensures MDC is populated before any service/advisor logic executes,
 * and is always cleaned up in the finally block to prevent Virtual Thread context leaks.
 *
 * <p>Architecture Note: ScopedValues propagate natively into child Virtual Threads, but SLF4J MDC does not.
 * This filter acts as the "bridge" at the HTTP boundary. For background tasks dispatched via
 * {@code ContextSnapshotFactory}, the MDC is re-populated by the advisor or snapshot wrapper itself.</p>
 *
 * State: Stateless
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AgentMdcFilter extends OncePerRequestFilter {

    /** MDC key constants — used consistently across logback-spring.xml patterns and LogstashEncoder includes. */
    public static final String MDC_RUN_ID = "runId";
    public static final String MDC_SESSION_ID = "sessionId";
    public static final String MDC_AGENT_ID = "agentId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_ORG_ID = "orgId";
    public static final String MDC_PHASE = "phase";
    public static final String MDC_ORCHESTRATION_DEPTH = "orchestrationDepth";

    /**
     * @summary Populates SLF4J MDC with agentic context from AgentContextHolder ScopedValues on every HTTP request.
     * @logic
     * 1. Reads all bound ScopedValues from AgentContextHolder (runId, sessionId, userId, orgId, orchestrationDepth).
     * 2. Puts non-null values into MDC.
     * 3. Executes the filter chain (all downstream controllers, services, advisors will inherit these MDC values).
     * 4. Clears all MDC keys in a finally block to prevent stale data on pooled carrier threads.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            populateMdc();
            filterChain.doFilter(request, response);
        } finally {
            clearMdc();
        }
    }

    /**
     * @summary Reads from AgentContextHolder ScopedValues and populates SLF4J MDC.
     * @logic Each ScopedValue is checked for binding before reading to avoid IllegalStateException on unbound values.
     */
    public static void populateMdc() {
        putIfPresent(MDC_RUN_ID, AgentContextHolder.getCurrentRunId());
        putIfPresent(MDC_SESSION_ID, AgentContextHolder.getSessionId());
        putIfPresent(MDC_USER_ID, AgentContextHolder.getUserId());
        putIfPresent(MDC_ORG_ID, AgentContextHolder.getOrgId());

        Integer depth = AgentContextHolder.getOrchestrationDepth();
        if (depth != null && depth > 0) {
            MDC.put(MDC_ORCHESTRATION_DEPTH, String.valueOf(depth));
        }
    }

    /**
     * @summary Sets a specific MDC key for a named agent or phase. Called by AgentLoggingAdvisor to inject per-call context.
     * @logic Simple delegation to MDC.put, guarded by null check.
     */
    public static void setAgentId(String agentId) {
        putIfPresent(MDC_AGENT_ID, agentId);
    }

    /**
     * @summary Sets the current agentic phase (e.g., LLM_RPC_START, TOOL_INVOCATION) into MDC.
     * @logic Simple delegation to MDC.put, guarded by null check.
     */
    public static void setPhase(String phase) {
        putIfPresent(MDC_PHASE, phase);
    }

    /**
     * @summary Removes all agentic MDC keys to prevent context leaking across Virtual Thread carrier reuse.
     * @logic Iterates over all known MDC key constants and removes them.
     */
    public static void clearMdc() {
        MDC.remove(MDC_RUN_ID);
        MDC.remove(MDC_SESSION_ID);
        MDC.remove(MDC_AGENT_ID);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_ORG_ID);
        MDC.remove(MDC_PHASE);
        MDC.remove(MDC_ORCHESTRATION_DEPTH);
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }
}
