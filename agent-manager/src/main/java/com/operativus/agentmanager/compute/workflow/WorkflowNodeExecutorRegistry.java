package com.operativus.agentmanager.compute.workflow;

import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domain Responsibility: Resolves the {@link WorkflowNodeExecutor} for a given {@link NodeKind}
 *     (DAG plan §2.4). The frontier scheduler ({@code DagWorkflowExecutor}) dispatches each ready
 *     node to the executor returned here. Built once from all {@link WorkflowNodeExecutor} beans on
 *     the classpath; the set grows per phase (AGENT now, then Function/Condition/Router/Loop/...).
 *     A duplicate kind is a wiring error and fails fast at construction.
 * State: Stateless (immutable kind→executor map).
 */
@Component
public class WorkflowNodeExecutorRegistry {

    private final Map<NodeKind, WorkflowNodeExecutor> byKind = new EnumMap<>(NodeKind.class);

    public WorkflowNodeExecutorRegistry(List<WorkflowNodeExecutor> executors) {
        for (WorkflowNodeExecutor executor : executors) {
            WorkflowNodeExecutor existing = byKind.putIfAbsent(executor.kind(), executor);
            if (existing != null) {
                throw new IllegalStateException("Duplicate WorkflowNodeExecutor for kind " + executor.kind()
                        + ": " + existing.getClass().getName() + " vs " + executor.getClass().getName());
            }
        }
    }

    /** The executor for the kind, or empty when no executor is registered (kind unsupported in this phase). */
    public Optional<WorkflowNodeExecutor> resolve(NodeKind kind) {
        return Optional.ofNullable(byKind.get(kind));
    }
}
