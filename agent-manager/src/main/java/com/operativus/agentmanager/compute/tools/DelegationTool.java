package com.operativus.agentmanager.compute.tools;

import org.springframework.beans.factory.ObjectProvider;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.compute.teams.OrchestrationMemoryScopes;
import com.operativus.agentmanager.compute.teams.TransitionValidator;
import com.operativus.agentmanager.compute.teams.TierEscalationValidator;
import com.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;

import com.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Domain Responsibility: Provides a generic Spring AI tool allowing a Leader/Coordinator agent to logically delegate sub-tasks to subordinate agents.
 * Validates DAG constraints and tier escalation before delegating, and propagates ephemeral workflow context to child agents.
 *     Emits DELEGATION_START / DELEGATION_COMPLETE events to {@link AgentRunEventBus} for the parent run's timeline (logging plan §5.12).
 * State: Stateless
 */
@AgentToolComponent
public class DelegationTool {

    private static final Logger log = LoggerFactory.getLogger(DelegationTool.class);

    private final ObjectProvider<AgentOperations> agentRunnerProvider;
    private final ObjectProvider<AgentRegistry> agentRegistryProvider;
    private final TransitionValidator transitionValidator;
    private final TierEscalationValidator tierEscalationValidator;
    private final EphemeralSwarmContext ephemeralSwarmContext;
    private final AgentRunEventBus eventBus;

    public DelegationTool(ObjectProvider<AgentOperations> agentRunnerProvider,
                          ObjectProvider<AgentRegistry> agentRegistryProvider,
                          TransitionValidator transitionValidator,
                          TierEscalationValidator tierEscalationValidator,
                          EphemeralSwarmContext ephemeralSwarmContext,
                          AgentRunEventBus eventBus) {
        this.agentRunnerProvider = agentRunnerProvider;
        this.agentRegistryProvider = agentRegistryProvider;
        this.transitionValidator = transitionValidator;
        this.tierEscalationValidator = tierEscalationValidator;
        this.ephemeralSwarmContext = ephemeralSwarmContext;
        this.eventBus = eventBus;
    }

    /**
     * @summary Delegates a task to another agent and returns its response.
     * @logic
     * 1. Extracts the current agent context (sessionId, userId, orgId, workflowRunId) from ScopedValues.
     * 2. Validates the transition against DAG constraints and security tier boundaries.
     * 3. Propagates the parent's ephemeral swarm context to the child's session scope.
     * 4. Executes the delegated run synchronously and returns the content.
     */
    @RequiresCapability("team_orchestration")
    @Tool(description = "Delegates a task to another agent. Returns the response from that agent.")
    public String delegate_to_agent(
        @ToolParam(description = "The ID of the agent to call") String agentId,
        @ToolParam(description = "The task or question for the agent") String task
    ) {
        log.info("Delegating to agent: {} with task: {}", agentId, task);
        long start = System.currentTimeMillis();
        publishDelegationEvent(AgentRunEventType.DELEGATION_START, buildStartPayload(agentId, task));

        String childRunId = null;
        String status = "ok";
        String errorClass = null;
        String errorMessage = null;
        int contentLength = 0;
        try {
            String parentSessionId = AgentContextHolder.getSessionId();
            String parentUserId = AgentContextHolder.getUserId();
            String parentOrgId = AgentContextHolder.getOrgId();
            // Source for DAG + tier validators is the *agent* doing the delegating,
            // not the run UUID. AgentService binds both currentRunId (a UUID) and
            // agentId (the parent agent's id) — the validators look up agent
            // definitions by id, so we must feed them the agent id. Reading
            // currentRunId here previously caused TierEscalationValidator to fail
            // closed on every production call (UUID never resolves to an agent).
            String sourceAgentId = AgentContextHolder.getAgentId();
            String workflowRunId = AgentContextHolder.getWorkflowRunId();
            String teamRootId = AgentContextHolder.getTeamRootId();

            // DAG constraint validation against the enclosing team (if any). teamRootId
            // is bound by TeamOrchestrationEngine.executeSync/executeStream to the
            // current team's root agent id; tool calls outside a team scope leave it
            // null, which TransitionValidator (line 33-37) treats as "no team context —
            // skip DAG validation". team_transition_edges rows key on the team root
            // agent id, so this is the value that actually resolves the DAG.
            transitionValidator.validate(teamRootId, sourceAgentId, agentId);

            // Security tier escalation check
            if (sourceAgentId != null) {
                tierEscalationValidator.validate(sourceAgentId, agentId, parentOrgId);
            }

            // Propagate ephemeral context to child session scope
            String delegateSessionId = parentSessionId != null ? parentSessionId : "delegated-" + System.currentTimeMillis();
            if (workflowRunId != null) {
                ephemeralSwarmContext.mergeFrom(workflowRunId, delegateSessionId);
            }

            // §9 MEM-2: if the delegating parent is a team with isolateMemory=true, derive a
            // per-child conversationId so the child's MessageChatMemoryAdvisor keeps its own
            // bucket — mirrors the orchestrator path. Lookup is best-effort: if the parent
            // can't be resolved (orphan run, deleted definition) we fall back to the bare
            // session id, matching the pre-§9-MEM-2 behaviour.
            AgentDefinition parentDef = resolveParentDefinition();
            String childConversationId = OrchestrationMemoryScopes.memberConversationId(
                    parentDef, delegateSessionId, agentId);

            RunResponse response = agentRunnerProvider.getIfAvailable().run(agentId, task, null, childConversationId, parentUserId, parentOrgId, false, null);
            childRunId = response.runId();
            String content = response.content();
            contentLength = content != null ? content.length() : 0;
            return content;
        } catch (ResourceNotFoundException | BusinessValidationException e) {
            status = "error";
            errorClass = e.getClass().getSimpleName();
            errorMessage = e.getMessage();
            return "Agent is unavailable or inactive: " + e.getMessage();
        } catch (com.operativus.agentmanager.core.exception.SwarmEscalationException e) {
            status = "error";
            errorClass = e.getClass().getSimpleName();
            errorMessage = e.getMessage();
            return "Delegation blocked: Security tier escalation requires human approval. " + e.getMessage();
        } catch (Exception e) {
            status = "error";
            errorClass = e.getClass().getSimpleName();
            errorMessage = e.getMessage();
            return "Failed to delegate to agent " + agentId + ": " + e.getMessage();
        } finally {
            publishDelegationEvent(AgentRunEventType.DELEGATION_COMPLETE,
                    buildCompletePayload(agentId, status, childRunId, contentLength,
                            errorClass, errorMessage, System.currentTimeMillis() - start));
        }
    }

    /**
     * @summary Resolve the AgentDefinition that owns the in-progress delegation, so the
     *     §9 MEM-2 isolateMemory flag can be consulted.
     * @logic Reads the parent agent id from {@link AgentContextHolder#getAgentId()} and
     *     looks it up via the lazy {@link AgentRegistry}. Returns {@code null} silently
     *     if the registry is unavailable, the agent id is missing, or the definition
     *     can't be found — {@link OrchestrationMemoryScopes#memberConversationId} treats
     *     null parent as "no isolation" so the dispatch falls back to the bare session id.
     */
    private AgentDefinition resolveParentDefinition() {
        AgentRegistry registry = agentRegistryProvider.getIfAvailable();
        if (registry == null) return null;
        String parentAgentId = AgentContextHolder.getAgentId();
        if (parentAgentId == null) return null;
        try {
            return registry.findById(parentAgentId, AgentContextHolder.getOrgId());
        } catch (Exception e) {
            log.debug("Parent AgentDefinition lookup failed (id={}): {}", parentAgentId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildStartPayload(String targetAgentId, String task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetAgentId", targetAgentId);
        putAgentNames(payload, targetAgentId);
        payload.put("taskLength", task != null ? task.length() : 0);
        String workflowRunId = AgentContextHolder.getWorkflowRunId();
        if (workflowRunId != null) payload.put("workflowRunId", workflowRunId);
        return payload;
    }

    private Map<String, Object> buildCompletePayload(String targetAgentId, String status, String childRunId,
                                                     int contentLength, String errorClass, String errorMessage,
                                                     long durationMs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetAgentId", targetAgentId);
        putAgentNames(payload, targetAgentId);
        payload.put("status", status);
        payload.put("durationMs", durationMs);
        if (childRunId != null) payload.put("childRunId", childRunId);
        payload.put("contentLength", contentLength);
        if (errorClass != null) payload.put("errorClass", errorClass);
        if (errorMessage != null) payload.put("errorMessage", errorMessage);
        return payload;
    }

    /**
     * @summary Stamps human-readable source/target agent names (and the source id) onto a delegation
     *     event payload so the timeline UI and structured log show "Coordinator → Research Assistant"
     *     instead of two bare UUIDs.
     * @logic Best-effort registry lookups; any failure leaves the name absent rather than breaking the
     *     event. Names are additive — the {@code targetAgentId} (and other id fields) remain so both the
     *     name and the id are available downstream.
     */
    private void putAgentNames(Map<String, Object> payload, String targetAgentId) {
        String sourceAgentId = AgentContextHolder.getAgentId();
        if (sourceAgentId != null) payload.put("sourceAgentId", sourceAgentId);
        String sourceAgentName = resolveAgentName(sourceAgentId);
        if (sourceAgentName != null) payload.put("sourceAgentName", sourceAgentName);
        String targetAgentName = resolveAgentName(targetAgentId);
        if (targetAgentName != null) payload.put("targetAgentName", targetAgentName);
    }

    /**
     * @summary Best-effort {@code agentId → display name} lookup for delegation-event readability.
     * @logic Returns the agent's name, or null when the registry is unavailable or the id does not
     *     resolve. Never throws — name resolution must not break a delegation.
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

    private void publishDelegationEvent(AgentRunEventType type, Map<String, Object> payload) {
        if (eventBus == null) return;
        String runId = AgentContextHolder.getCurrentRunId();
        if (runId == null) return; // delegation invoked outside an agent run — skip timeline event
        try {
            AgentRunEvent event = new AgentRunEvent(
                    type,
                    runId,
                    AgentContextHolder.getAgentId(),
                    null,
                    AgentContextHolder.getSessionId(),
                    AgentContextHolder.getOrgId(),
                    AgentContextHolder.getOrchestrationDepth(),
                    payload,
                    Instant.now());
            eventBus.publish(event);
        } catch (RuntimeException ex) {
            // R-18: isolation — event publication must never break delegation
            log.warn("Failed to publish {} event runId={}", type, runId, ex);
        }
    }
}

