package com.operativus.agentmanager.control.gateway;

import com.operativus.agentmanager.control.security.AgentContextHolder;
import com.operativus.agentmanager.control.team.ManifestParser;
import com.operativus.agentmanager.core.model.TeamManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.annotation.Timed;
import org.springframework.stereotype.Service;

/**
 * Serves as the Zero Trust API Interceptor for all internal Agent workflows.
 * Evaluates incoming requests against FinOps budgets and entity rules
 * before releasing the lock to the model orchestrator.
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private final ManifestParser manifestParser;
    private final com.operativus.agentmanager.control.security.GatewayPromptInjectionScanner promptInjectionScanner;

    public GatewayService(ManifestParser manifestParser, com.operativus.agentmanager.control.security.GatewayPromptInjectionScanner promptInjectionScanner) {
        this.manifestParser = manifestParser;
        this.promptInjectionScanner = promptInjectionScanner;
    }

    /**
     * Intercepts and strictly wraps a workflow execution request to enforce FinOps policies
     * and Zero-Trust identity context propagation.
     * @param teamId The team executing the workflow.
     * @param requestedAgentId The specific agent triggered.
     * @param execution The Spring AI chat execution callback.
     * @return T the result of the execution.
     */
    @Timed(value = "agent.gateway.execution", description = "Time taken to evaluate security gateway bounds and execute the agent")
    @io.micrometer.observation.annotation.Observed(name = "agent.gateway.session")
    public <T> T executeWithContext(String teamId, String requestedAgentId, java.util.concurrent.Callable<T> execution) throws Exception {
        log.info("Gateway intercepting workflow request for Team: {}, Agent: {}", teamId, requestedAgentId);
        
        TeamManifest manifest = manifestParser.getManifestForTeam(teamId);
        if (manifest == null) {
            log.warn("Gateway blocked execution: Missing or invalid TeamManifest for Team {}", teamId);
            throw new SecurityException("Gateway blocked: Missing TeamManifest");
        }

        // Verify FinOps envelope
        if (manifest.maxDailySpend() != null && manifest.maxDailySpend() <= 0.0) {
            log.warn("Gateway blocked execution: Daily spend budget exhausted for Team {}", teamId);
            throw new SecurityException("Gateway blocked: Daily Budget Exhausted");
        }

        // Verify the requested agent exists mapped in the active manifest
        if (manifest.agents() == null || !manifest.agents().containsKey(requestedAgentId)) {
            log.warn("Gateway blocked execution: Agent {} is not structurally mapped to Team {}", requestedAgentId, teamId);
            throw new SecurityException("Gateway blocked: Unmapped Agent");
        }

        TeamManifest.AgentManifest agentMeta = manifest.agents().get(requestedAgentId);
        log.debug("Gateway approved execution phase for User/Agent context. Propagating capabilities: {}", agentMeta.capabilities());
        
        promptInjectionScanner.scanCapabilities(agentMeta.capabilities());

        // Phase 5: NLP / DLP Boundary Injection
        if (Boolean.TRUE.equals(agentMeta.requiresPiiRedaction())) {
            log.info("DLP (Data Loss Prevention) boundary engaged for Agent: {}. PII will be redacted from LLM prompt streams.", requestedAgentId);
        }
        
        // Setup Virtual Thread safe identity
        AgentContextHolder.AgentContext context = new AgentContextHolder.AgentContext(
            teamId,
            "system_user", // NOTE: This will be dynamically fetched from standard Spring Security Context later
            manifest.maxDailySpend(),
            agentMeta
        );
        
        log.debug("AgentSecurityContext successfully bound to ScopedValue for Tool Registry safety.");
        return ScopedValue.where(AgentContextHolder.CONTEXT, context).call(execution::call);
    }
}
