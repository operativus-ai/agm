package com.operativus.agentmanager.compute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.compute.teams.OrchestrationStrategy;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.EventType;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.ToolCallDTO;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.registry.RunOperations;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.control.config.SecurityContextUtils;
import com.operativus.agentmanager.core.model.TenantConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Dispatches multi-agent team executions to the correct OrchestrationStrategy — both synchronous and reactive stream variants.
 * State: Stateless (strategies map is read-only after construction)
 */
@Service
public class TeamOrchestrationEngine {

    private static final Logger log = LoggerFactory.getLogger(TeamOrchestrationEngine.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, OrchestrationStrategy> strategies;
    private final RunOperations runRepository;
    private final SessionRepository sessionRepository;
    private final AgentRunFinalizer agentRunFinalizer;

    public TeamOrchestrationEngine(List<OrchestrationStrategy> strategies,
                                    RunOperations runRepository,
                                    SessionRepository sessionRepository,
                                    AgentRunFinalizer agentRunFinalizer) {
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(
                        s -> s.getStrategyName().toUpperCase(), s -> s));
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.agentRunFinalizer = agentRunFinalizer;
        log.info("TeamOrchestrationEngine registered {} strategies: {}", this.strategies.size(), this.strategies.keySet());
    }

    /**
     * @summary Executes a team orchestration synchronously, using the pre-created AgentRun record.
     * @logic Wraps execution in ScopedValue context so child agent runs see this team run as their parent.
     */
    public RunResponse executeSync(AgentDefinition def, String userInput, List<Media> media,
                                    String effectiveSessionId, String userId, String orgId,
                                    Boolean generateFollowups, AgentOperations agentService,
                                    String executionRunId, AgentRun runRecord, Integer currentDepth) {
        OrchestrationStrategy strategy = resolveStrategy(def.teamMode());

        final List<ToolCallDTO> capturedTraces = Collections.synchronizedList(new ArrayList<>());
        String teamOutput = ScopedValue.where(AgentContextHolder.currentRunId, executionRunId)
                .where(AgentContextHolder.orchestrationDepth, currentDepth + 1)
                .where(AgentContextHolder.userId, userId != null ? userId : AgentContextHolder.getUserId())
                .where(AgentContextHolder.sessionId, effectiveSessionId)
                .where(AgentContextHolder.orgId, orgId != null ? orgId : AgentContextHolder.getOrgId())
                .where(AgentContextHolder.agentId, def.id())
                .where(AgentContextHolder.agentName, def.name() != null ? def.name() : def.id())
                .where(AgentContextHolder.teamRootId, def.id())
                .where(AgentContextHolder.toolTraces, Collections.synchronizedList(new ArrayList<ToolCallDTO>()))
                .where(AgentContextHolder.workflowRunId, new String[]{executionRunId})
                .call(() -> {
                    AgentContextHolder.populateMdcFromScopedValues();
                    try {
                        String result = strategy.execute(def, userInput, media, effectiveSessionId, userId, orgId, generateFollowups, agentService);
                        capturedTraces.addAll(AgentContextHolder.getTraces());
                        return result;
                    } finally {
                        AgentContextHolder.clearMdcFromScopedValues();
                    }
                });

        // Terminal-state persistence is owned by AgentRunFinalizer, which AgentService.run
        // invokes via finalizeRunRecord() immediately after this method returns. Writing the
        // row here directly would burn a redundant @Version increment and leave the row in
        // COMPLETED-without-telemetry between this save and the caller's finalize.
        //
        // executeSync is invoked from AgentService.run inside a ScopedValue.where chain
        // that binds AgentContextHolder.telemetry (line ~208). The accumulator is read
        // through the bound ScopedValue rather than threaded as a parameter to keep the
        // method signature stable. fromTelemetry throws on an unbound accumulator (A19).
        return new RunResponse(
                executionRunId,
                effectiveSessionId,
                teamOutput,
                new HashMap<>(),
                new ArrayList<>(capturedTraces),
                new ArrayList<>(),
                RunStatus.COMPLETED,
                RunMetricsBuilder.fromTelemetry(AgentContextHolder.getTelemetry())
        );
    }

    /**
     * @summary Emulates streaming for team executions by wrapping synchronous orchestration in a reactive Flux.
     * @logic Creates a new run record, delegates to the strategy on boundedElastic scheduler, emits content and stop events.
     */
    public Flux<AgentStreamEvent> executeStream(AgentDefinition def, String userInput, List<Media> media,
                                                 String effectiveSessionId, String userId, String orgId,
                                                 Boolean generateFollowups, AgentOperations agentService,
                                                 Integer currentDepth, String parentRunId) {
        // Resolve strategy synchronously, BEFORE any side effects (session create, run-row save).
        // Mirrors executeSync's pattern (resolveStrategy throws on invalid teamMode). On failure,
        // return Flux.error so the caller's reactive pipeline observes the error WITHOUT a phantom
        // RUNNING row leaking. Closes Tier 2.5 audit F4.
        final OrchestrationStrategy strategy;
        try {
            strategy = resolveStrategy(def.teamMode());
        } catch (UnsupportedOperationException ex) {
            return Flux.error(ex);
        }

        String executionRunId = UUID.randomUUID().toString();
        ensureSessionExists(effectiveSessionId, def.id(), userId, orgId);

        AgentRun runRecord = new AgentRun(def.id(), effectiveSessionId, userInput, userId, orgId);
        runRecord.setId(executionRunId);
        runRecord.setParentRunId(parentRunId);
        runRecord.setStatus(RunStatus.RUNNING);
        runRepository.save(runRecord);

        return Flux.create(sink -> {
            sink.next(new AgentStreamEvent(EventType.START, "", System.currentTimeMillis()));
            sink.next(new AgentStreamEvent(EventType.TOOL_START, "Initiating " + def.teamMode() + " Team Orchestration...", System.currentTimeMillis()));

            reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
                try {
                    final List<ToolCallDTO> capturedTraces = Collections.synchronizedList(new ArrayList<>());
                    String teamOutput = ScopedValue.where(AgentContextHolder.currentRunId, executionRunId)
                            .where(AgentContextHolder.orchestrationDepth, currentDepth + 1)
                            .where(AgentContextHolder.userId, userId != null ? userId : AgentContextHolder.getUserId())
                            .where(AgentContextHolder.sessionId, effectiveSessionId)
                            .where(AgentContextHolder.orgId, orgId != null ? orgId : AgentContextHolder.getOrgId())
                            .where(AgentContextHolder.agentId, def.id())
                            .where(AgentContextHolder.agentName, def.name() != null ? def.name() : def.id())
                            .where(AgentContextHolder.teamRootId, def.id())
                            .where(AgentContextHolder.toolTraces, Collections.synchronizedList(new ArrayList<ToolCallDTO>()))
                            .where(AgentContextHolder.workflowRunId, new String[]{executionRunId})
                            .call(() -> {
                                AgentContextHolder.populateMdcFromScopedValues();
                                try {
                                    String result = strategy.execute(def, userInput, media, effectiveSessionId, userId, orgId, generateFollowups, agentService);
                                    capturedTraces.addAll(AgentContextHolder.getTraces());
                                    return result;
                                } finally {
                                    AgentContextHolder.clearMdcFromScopedValues();
                                }
                            });

                    // Terminal write owned by AgentRunFinalizer — same contract as the sync
                    // path. The accumulator is intentionally null on this path; binding +
                    // aggregation across sub-runs is tracked under gap #19 (separate spec).
                    agentRunFinalizer.finalizeRun(executionRunId, RunStatus.COMPLETED,
                            teamOutput, null, null);

                    sink.next(new AgentStreamEvent(EventType.CONTENT_DELTA, teamOutput, System.currentTimeMillis()));

                    // Streaming-team STOP-event contract: this RunResponse is serialized into a STOP
                    // AgentStreamEvent and sent to the streaming sink. The Reactor boundedElastic
                    // scheduler does not propagate ScopedValue<AgentContextHolder.telemetry>, so the
                    // accumulator is intentionally NOT bound on this path. Use noTelemetry() —
                    // explicit construction for a path where telemetry was never expected to exist.
                    // Streaming inline metrics are out of scope for gap #19 (separate spec).
                    RunResponse responseObj = new RunResponse(executionRunId, effectiveSessionId, teamOutput,
                            new HashMap<>(), new ArrayList<>(capturedTraces), new ArrayList<>(), RunStatus.COMPLETED,
                            RunMetricsBuilder.noTelemetry());
                    try {
                        String json = OBJECT_MAPPER.writeValueAsString(responseObj);
                        sink.next(new AgentStreamEvent(EventType.STOP, json, System.currentTimeMillis()));
                    } catch (Exception e) {
                        sink.next(new AgentStreamEvent(EventType.STOP, "", System.currentTimeMillis()));
                    }
                    sink.complete();
                } catch (Exception e) {
                    log.error("Team orchestration stream failed", e);
                    agentRunFinalizer.finalizeRun(executionRunId, RunStatus.FAILED,
                            "Error: " + e.getMessage(), null, null);
                    sink.error(e);
                }
            });
        });
    }

    private OrchestrationStrategy resolveStrategy(String teamMode) {
        if (teamMode == null || teamMode.isBlank()) {
            throw new UnsupportedOperationException(
                    "teamMode is null or blank — agent must declare a team mode (e.g. SEQUENTIAL, COORDINATOR, SWARM, ROUTER, PLANNER).");
        }
        OrchestrationStrategy strategy = strategies.get(teamMode.toUpperCase());
        if (strategy == null) {
            throw new UnsupportedOperationException("Unsupported team mode: " + teamMode);
        }
        return strategy;
    }

    private void ensureSessionExists(String sessionId, String agentId, String userId, String orgId) {
        String resolvedUser = userId != null ? userId : SecurityContextUtils.resolveCurrentUserId();
        String resolvedOrg = orgId != null && !orgId.isBlank() ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
        sessionRepository.upsertEnsureExists(sessionId, agentId, resolvedUser, resolvedOrg);
    }
}
