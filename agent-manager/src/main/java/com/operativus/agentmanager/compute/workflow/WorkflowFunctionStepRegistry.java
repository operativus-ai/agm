package com.operativus.agentmanager.compute.workflow;

import com.operativus.agentmanager.core.spi.WorkflowFunctionStep;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Domain Responsibility: Resolves the {@link WorkflowFunctionStep} for a FUNCTION node's key
 *     (REQ-DR-5, DAG-6). Built once from all {@link WorkflowFunctionStep} beans on the classpath
 *     (none ship by default — functions are deployment extensions, hence {@link ObjectProvider}
 *     rather than {@code List} so an empty universe is not a wiring error). Keys are
 *     case-insensitive; a duplicate key is a wiring error and fails fast at construction,
 *     mirroring {@link WorkflowNodeExecutorRegistry}.
 * State: Stateless (immutable key→function map).
 */
@Component
public class WorkflowFunctionStepRegistry {

    private final Map<String, WorkflowFunctionStep> byKey = new HashMap<>();

    public WorkflowFunctionStepRegistry(ObjectProvider<WorkflowFunctionStep> steps) {
        steps.forEach(step -> {
            String key = normalize(step.key());
            if (key == null) {
                throw new IllegalStateException("WorkflowFunctionStep " + step.getClass().getName()
                        + " declares a null/blank key");
            }
            WorkflowFunctionStep existing = byKey.putIfAbsent(key, step);
            if (existing != null) {
                throw new IllegalStateException("Duplicate WorkflowFunctionStep for key '" + key
                        + "': " + existing.getClass().getName() + " vs " + step.getClass().getName());
            }
        });
    }

    /** The function for the key (case-insensitive), or empty when none is registered. */
    public Optional<WorkflowFunctionStep> byKey(String key) {
        String normalized = normalize(key);
        return normalized == null ? Optional.empty() : Optional.ofNullable(byKey.get(normalized));
    }

    /** The registered keys, for diagnostics on an unknown-key failure. */
    public Set<String> keys() {
        return Set.copyOf(byKey.keySet());
    }

    private static String normalize(String key) {
        if (key == null || key.isBlank()) return null;
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
