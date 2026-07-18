package com.operativus.agentmanager.compute.config;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.ToolCallDTO;
import org.springframework.core.task.TaskDecorator;

import java.util.List;
import java.util.Set;

/**
 * Domain Responsibility: Captures the caller thread's {@link AgentContextHolder} ScopedValues at
 * task-submit time and rebinds them on the worker thread, then bridges the bound values into MDC
 * so structured logs on async work carry runId/orgId/userId.
 *
 * <p>Spring's {@code ContextPropagatingTaskDecorator} (Micrometer) propagates Reactor Context and
 * registered {@code ThreadLocalAccessor}s but does NOT propagate JDK 21 {@code ScopedValue}s. Without
 * this decorator, every {@code @Async} method on the global Spring task executor loses the request
 * context — RAG/PII/cultural-memory advisors silently default to {@code null orgId}, breaking tenant
 * isolation. Closes Tier 1.2 audit finding F23.</p>
 *
 * <p>Compose this decorator <i>outer</i> to {@code ContextPropagatingTaskDecorator} so ScopedValues
 * are bound first when the worker runs and any nested propagation logic sees them through
 * {@code AgentContextHolder.get*()}.</p>
 *
 * State: Stateless.
 */
public final class ScopedValueTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture on the caller thread (decorate-time runs synchronously when the task is submitted).
        final String orgId = AgentContextHolder.getOrgId();
        final String userIdRaw = AgentContextHolder.getUserId();
        // Drop the SYSTEM_PRINCIPAL fallback — it isn't a real bound value, just the no-auth default.
        final String userId = SecurityPrincipals.SYSTEM_PRINCIPAL.equals(userIdRaw) ? null : userIdRaw;
        final String sessionId = AgentContextHolder.getSessionId();
        final String agentId = AgentContextHolder.getAgentId();
        final String agentName = AgentContextHolder.getAgentName();
        final String teamRootId = AgentContextHolder.getTeamRootId();
        final String currentRunId = AgentContextHolder.getCurrentRunId();
        final boolean depthBound = AgentContextHolder.orchestrationDepth.isBound();
        final int depth = depthBound ? AgentContextHolder.orchestrationDepth.get() : 0;
        final RunTelemetryAccumulator telemetry = AgentContextHolder.getTelemetry();
        final List<ToolCallDTO> toolTraces = AgentContextHolder.toolTraces.isBound()
                ? AgentContextHolder.toolTraces.get() : null;
        final Set<String> approvedTools = AgentContextHolder.approvedTools.isBound()
                ? AgentContextHolder.approvedTools.get() : null;
        final String[] workflowRunId = AgentContextHolder.workflowRunId.isBound()
                ? AgentContextHolder.workflowRunId.get() : null;
        final List<String> allowedKbIds = AgentContextHolder.allowedKnowledgeBaseIds.isBound()
                ? AgentContextHolder.allowedKnowledgeBaseIds.get() : null;
        final boolean requiresEncryptionBound = AgentContextHolder.requiresEncryption.isBound();
        final boolean requiresEncryption = requiresEncryptionBound
                ? Boolean.TRUE.equals(AgentContextHolder.requiresEncryption.get()) : false;

        return () -> {
            // Build the binding chain conditionally — ScopedValue.where requires non-null values,
            // and binding sentinel defaults would change the semantics of get*()'s isBound() check.
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
                        ? ScopedValue.where(AgentContextHolder.orchestrationDepth, depth)
                        : carrier.where(AgentContextHolder.orchestrationDepth, depth);
            }
            carrier = bind(carrier, AgentContextHolder.telemetry, telemetry);
            carrier = bind(carrier, AgentContextHolder.toolTraces, toolTraces);
            carrier = bind(carrier, AgentContextHolder.approvedTools, approvedTools);
            carrier = bind(carrier, AgentContextHolder.workflowRunId, workflowRunId);
            carrier = bind(carrier, AgentContextHolder.allowedKnowledgeBaseIds, allowedKbIds);
            if (requiresEncryptionBound) {
                carrier = (carrier == null)
                        ? ScopedValue.where(AgentContextHolder.requiresEncryption, requiresEncryption)
                        : carrier.where(AgentContextHolder.requiresEncryption, requiresEncryption);
            }

            Runnable mdcWrapped = () -> {
                AgentContextHolder.populateMdcFromScopedValues();
                try {
                    runnable.run();
                } finally {
                    AgentContextHolder.clearMdcFromScopedValues();
                }
            };

            if (carrier == null) {
                // Nothing was bound on the caller — run as-is.
                mdcWrapped.run();
            } else {
                carrier.run(mdcWrapped);
            }
        };
    }

    private static <T> ScopedValue.Carrier bind(ScopedValue.Carrier carrier, ScopedValue<T> key, T value) {
        if (value == null) return carrier;
        return (carrier == null) ? ScopedValue.where(key, value) : carrier.where(key, value);
    }
}
