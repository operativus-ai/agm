package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.compute.teams.TransitionValidator;
import ai.operativus.agentmanager.compute.teams.TierEscalationValidator;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;

import ai.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Domain Responsibility: Provides a Spring AI tool enabling "Swarm" style agents to voluntarily surrender control and pass conversational context to a different specialized agent.
 * Validates DAG transition constraints and security tier escalation boundaries before initiating the handoff.
 * State: Stateless
 */
@AgentToolComponent
public class HandOffTool {

    private static final Logger log = LoggerFactory.getLogger(HandOffTool.class);

    private final TransitionValidator transitionValidator;
    private final TierEscalationValidator tierEscalationValidator;
    private final AgentRunEventBus eventBus;
    private final ObjectProvider<AgentRegistry> agentRegistryProvider;

    public HandOffTool(TransitionValidator transitionValidator, TierEscalationValidator tierEscalationValidator,
                       AgentRunEventBus eventBus, ObjectProvider<AgentRegistry> agentRegistryProvider) {
        this.transitionValidator = transitionValidator;
        this.tierEscalationValidator = tierEscalationValidator;
        this.eventBus = eventBus;
        this.agentRegistryProvider = agentRegistryProvider;
    }

    /**
     * @summary Hands off the current conversational context to a different specialized agent.
     * @logic
     * 1. Extracts the current agent ID from the ScopedValue context to determine the source agent.
     * 2. Validates the transition against DAG constraints (if configured for the team).
     * 3. Validates the security tier escalation boundary (throws SwarmEscalationException if tier increases).
     * 4. Throws SwarmHandOffException to signal the control loop to redirect execution.
     */
    @RequiresCapability("team_orchestration")
    @Tool(description = "Hands off the conversation to another specialized agent in the swarm. Use this ONLY when you determine another agent is better suited for the user's request.")
    public String hand_off_to_agent(
        @ToolParam(description = "The ID of the agent to hand off to") String agentId,
        @ToolParam(description = "A comprehensive summary of the context, what has been done, and what the new agent needs to do") String context
    ) {
        // Source for DAG + tier validators is the *agent* doing the handoff, not
        // the run UUID. AgentService binds both currentRunId (a UUID) and agentId
        // (the source agent's id) — the validators look up agent definitions by
        // id, so we must feed them the agent id. Reading currentRunId here
        // previously caused TierEscalationValidator to fail closed on every
        // production call (UUID never resolves to an agent).
        String sourceAgentId = AgentContextHolder.getAgentId();
        String teamRootId = AgentContextHolder.getTeamRootId();
        log.info("Swarm handoff requested: source='{}' → target='{}'", sourceAgentId, agentId);

        // DAG constraint validation against the enclosing team (if any). teamRootId is
        // bound by TeamOrchestrationEngine.executeSync/executeStream to the current
        // team's root agent id; tool calls outside a team scope leave it null, which
        // TransitionValidator (line 33-37) treats as "no team context — skip DAG
        // validation". team_transition_edges rows key on the team root agent id, so
        // this is the value that actually resolves the DAG.
        transitionValidator.validate(teamRootId, sourceAgentId, agentId);

        // Security tier escalation check
        if (sourceAgentId != null) {
            tierEscalationValidator.validate(sourceAgentId, agentId, AgentContextHolder.getOrgId());
        }

        // Validation passed — the handoff is happening. Emit a single HANDOFF timeline event
        // before the exception redirects the control loop. There is no COMPLETE pairing: the
        // target agent's execution surfaces as its own RUN_START in the swarm loop.
        publishHandoffEvent(sourceAgentId, agentId, context);

        throw new ai.operativus.agentmanager.core.exception.SwarmHandOffException(agentId, context);
    }

    private void publishHandoffEvent(String sourceAgentId, String targetAgentId, String context) {
        if (eventBus == null) return;
        String runId = AgentContextHolder.getCurrentRunId();
        if (runId == null) return; // handoff invoked outside an agent run — skip timeline event
        try {
            Map<String, Object> payload = new HashMap<>();
            if (sourceAgentId != null) payload.put("sourceAgentId", sourceAgentId);
            String sourceAgentName = AgentContextHolder.getAgentName();
            if (sourceAgentName != null) payload.put("sourceAgentName", sourceAgentName);
            payload.put("targetAgentId", targetAgentId);
            String targetAgentName = resolveAgentName(targetAgentId);
            if (targetAgentName != null) payload.put("targetAgentName", targetAgentName);
            payload.put("contextLength", context != null ? context.length() : 0);

            AgentRunEvent event = new AgentRunEvent(
                    AgentRunEventType.HANDOFF,
                    runId,
                    sourceAgentId,
                    null,
                    AgentContextHolder.getSessionId(),
                    AgentContextHolder.getOrgId(),
                    AgentContextHolder.getOrchestrationDepth(),
                    payload,
                    Instant.now());
            eventBus.publish(event);
        } catch (RuntimeException ex) {
            // Isolation — event publication must never break the handoff control flow.
            log.warn("Failed to publish HANDOFF event runId={}", runId, ex);
        }
    }

    /**
     * @summary Best-effort {@code agentId → display name} lookup for handoff-event readability.
     * @logic Returns the target agent's name, or null when the registry is unavailable or the id
     *     does not resolve. Never throws — name resolution must not break a handoff.
     */
    private String resolveAgentName(String agentId) {
        if (agentId == null) return null;
        AgentRegistry registry = agentRegistryProvider.getIfAvailable();
        if (registry == null) return null;
        try {
            AgentDefinition def = registry.findById(agentId, AgentContextHolder.getOrgId());
            return def != null ? def.name() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

