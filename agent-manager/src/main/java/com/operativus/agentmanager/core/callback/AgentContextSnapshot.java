package com.operativus.agentmanager.core.callback;

import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.ToolCallDTO;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Domain Responsibility: Two-phase {@link AgentContextHolder} ScopedValue propagation across
 * cross-thread hops where JDK 21 ScopedValue does not propagate natively (Reactor schedulers,
 * fresh virtual threads, {@code @Async} executors).
 *
 * <p><b>Capture on caller, rebind on worker.</b> Call {@link #capture()} on the thread where
 * the values are bound (e.g. the request thread). Pass the returned snapshot across the thread
 * boundary, then invoke {@link #call(Callable)} or {@link #run(Runnable)} on the worker — the
 * snapshot rebinds each captured value via {@code ScopedValue.where(...)}, populates MDC for
 * structured-log correlation, and clears MDC on exit.</p>
 *
 * <p>Bindings that were unbound on the caller thread are skipped — never sentinel-bound to
 * non-null defaults — so {@code AgentContextHolder.get*()}'s {@code isBound()} checks remain
 * meaningful inside the rebind scope.</p>
 *
 * State: Stateless captured-values record.
 */
public final class AgentContextSnapshot {

    private final String orgId;
    private final String userId;
    private final String sessionId;
    private final String agentId;
    private final String agentName;
    private final String teamRootId;
    private final String currentRunId;
    private final boolean depthBound;
    private final int orchestrationDepth;
    private final boolean workflowDepthBound;
    private final int workflowDepth;
    private final RunTelemetryAccumulator telemetry;
    private final List<ToolCallDTO> toolTraces;
    private final Set<String> approvedTools;
    private final String[] workflowRunId;
    private final List<String> allowedKnowledgeBaseIds;
    private final boolean requiresEncryptionBound;
    private final boolean requiresEncryption;
    private final boolean emitGranularEventsBound;
    private final boolean emitGranularEvents;

    private AgentContextSnapshot(String orgId, String userId, String sessionId, String agentId,
                                 String agentName, String teamRootId, String currentRunId, boolean depthBound,
                                 int orchestrationDepth, boolean workflowDepthBound, int workflowDepth,
                                 RunTelemetryAccumulator telemetry, List<ToolCallDTO> toolTraces,
                                 Set<String> approvedTools, String[] workflowRunId,
                                 List<String> allowedKnowledgeBaseIds,
                                 boolean requiresEncryptionBound, boolean requiresEncryption,
                                 boolean emitGranularEventsBound, boolean emitGranularEvents) {
        this.orgId = orgId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.teamRootId = teamRootId;
        this.currentRunId = currentRunId;
        this.depthBound = depthBound;
        this.orchestrationDepth = orchestrationDepth;
        this.workflowDepthBound = workflowDepthBound;
        this.workflowDepth = workflowDepth;
        this.telemetry = telemetry;
        this.toolTraces = toolTraces;
        this.approvedTools = approvedTools;
        this.workflowRunId = workflowRunId;
        this.allowedKnowledgeBaseIds = allowedKnowledgeBaseIds;
        this.requiresEncryptionBound = requiresEncryptionBound;
        this.requiresEncryption = requiresEncryption;
        this.emitGranularEventsBound = emitGranularEventsBound;
        this.emitGranularEvents = emitGranularEvents;
    }

    /**
     * Captures all currently-bound {@link AgentContextHolder} ScopedValues from the calling
     * thread. Must be invoked on the thread that owns the bindings (e.g. the controller
     * thread before a Reactor {@code subscribeOn(boundedElastic)} hop).
     */
    public static AgentContextSnapshot capture() {
        String userIdRaw = AgentContextHolder.getUserId();
        // SYSTEM_PRINCIPAL is the no-auth fallback, not a real bound value — skip it so the
        // worker does not get a misleading bind that hides the unauthenticated state.
        String userId = SecurityPrincipals.SYSTEM_PRINCIPAL.equals(userIdRaw) ? null : userIdRaw;

        return new AgentContextSnapshot(
                AgentContextHolder.getOrgId(),
                userId,
                AgentContextHolder.getSessionId(),
                AgentContextHolder.getAgentId(),
                AgentContextHolder.getAgentName(),
                AgentContextHolder.getTeamRootId(),
                AgentContextHolder.getCurrentRunId(),
                AgentContextHolder.orchestrationDepth.isBound(),
                AgentContextHolder.orchestrationDepth.isBound()
                        ? AgentContextHolder.orchestrationDepth.get() : 0,
                AgentContextHolder.workflowDepth.isBound(),
                AgentContextHolder.workflowDepth.isBound()
                        ? AgentContextHolder.workflowDepth.get() : 0,
                AgentContextHolder.getTelemetry(),
                AgentContextHolder.toolTraces.isBound() ? AgentContextHolder.toolTraces.get() : null,
                AgentContextHolder.approvedTools.isBound() ? AgentContextHolder.approvedTools.get() : null,
                AgentContextHolder.workflowRunId.isBound() ? AgentContextHolder.workflowRunId.get() : null,
                AgentContextHolder.allowedKnowledgeBaseIds.isBound()
                        ? AgentContextHolder.allowedKnowledgeBaseIds.get() : null,
                AgentContextHolder.requiresEncryption.isBound(),
                AgentContextHolder.requiresEncryption.isBound()
                        && Boolean.TRUE.equals(AgentContextHolder.requiresEncryption.get()),
                AgentContextHolder.emitGranularEvents.isBound(),
                AgentContextHolder.emitGranularEvents.isBound()
                        && Boolean.TRUE.equals(AgentContextHolder.emitGranularEvents.get())
        );
    }

    /**
     * Rebinds the captured values on the current thread, populates MDC, runs {@code body},
     * then clears MDC. Checked exceptions thrown by {@code body} are unwrapped to runtime.
     */
    public <T> T call(Callable<T> body) {
        ScopedValue.Carrier carrier = buildCarrier();
        try {
            if (carrier == null) {
                AgentContextHolder.populateMdcFromScopedValues();
                try {
                    return body.call();
                } finally {
                    AgentContextHolder.clearMdcFromScopedValues();
                }
            }
            return carrier.call(() -> {
                AgentContextHolder.populateMdcFromScopedValues();
                try {
                    return body.call();
                } finally {
                    AgentContextHolder.clearMdcFromScopedValues();
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Void-returning variant for {@link Runnable} bodies. */
    public void run(Runnable body) {
        call(() -> { body.run(); return null; });
    }

    private ScopedValue.Carrier buildCarrier() {
        ScopedValue.Carrier carrier = null;
        carrier = bind(carrier, AgentContextHolder.orgId, orgId);
        carrier = bind(carrier, AgentContextHolder.userId, userId);
        carrier = bind(carrier, AgentContextHolder.sessionId, sessionId);
        carrier = bind(carrier, AgentContextHolder.agentId, agentId);
        carrier = bind(carrier, AgentContextHolder.agentName, agentName);
        carrier = bind(carrier, AgentContextHolder.teamRootId, teamRootId);
        carrier = bind(carrier, AgentContextHolder.currentRunId, currentRunId);
        if (depthBound) {
            carrier = (carrier == null)
                    ? ScopedValue.where(AgentContextHolder.orchestrationDepth, orchestrationDepth)
                    : carrier.where(AgentContextHolder.orchestrationDepth, orchestrationDepth);
        }
        if (workflowDepthBound) {
            carrier = (carrier == null)
                    ? ScopedValue.where(AgentContextHolder.workflowDepth, workflowDepth)
                    : carrier.where(AgentContextHolder.workflowDepth, workflowDepth);
        }
        carrier = bind(carrier, AgentContextHolder.telemetry, telemetry);
        carrier = bind(carrier, AgentContextHolder.toolTraces, toolTraces);
        carrier = bind(carrier, AgentContextHolder.approvedTools, approvedTools);
        carrier = bind(carrier, AgentContextHolder.workflowRunId, workflowRunId);
        carrier = bind(carrier, AgentContextHolder.allowedKnowledgeBaseIds, allowedKnowledgeBaseIds);
        if (requiresEncryptionBound) {
            carrier = (carrier == null)
                    ? ScopedValue.where(AgentContextHolder.requiresEncryption, requiresEncryption)
                    : carrier.where(AgentContextHolder.requiresEncryption, requiresEncryption);
        }
        if (emitGranularEventsBound) {
            carrier = (carrier == null)
                    ? ScopedValue.where(AgentContextHolder.emitGranularEvents, emitGranularEvents)
                    : carrier.where(AgentContextHolder.emitGranularEvents, emitGranularEvents);
        }
        return carrier;
    }

    private static <T> ScopedValue.Carrier bind(ScopedValue.Carrier carrier, ScopedValue<T> key, T value) {
        if (value == null) return carrier;
        return (carrier == null) ? ScopedValue.where(key, value) : carrier.where(key, value);
    }
}
