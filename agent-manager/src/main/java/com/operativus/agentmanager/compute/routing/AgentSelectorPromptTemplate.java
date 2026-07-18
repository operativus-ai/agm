package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.core.model.definitions.AgentDefinition;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Shared candidate-list renderer used by {@code RouterOrchestrator.determineTargetAgent}
 *     (ROUTER-strategy team selection) and by edition add-ons (the enterprise
 *     universal-dispatch classifier). The consumers compose different prompt preambles
 *     and structured-output schemas around this candidate block, but the candidate-list
 *     shape itself must stay consistent so the same LLM understands both calls identically.
 *
 *     <p>Per-candidate line shape:
 *     <pre>
 *     - {name} (ID: {id}): {description} [Capabilities: c1, c2] [Tools: t1, t2]
 *     </pre>
 *     Capability and tool brackets are omitted when the underlying list is empty.
 * State: Stateless (utility class)
 */
public final class AgentSelectorPromptTemplate {

    private AgentSelectorPromptTemplate() {}

    /**
     * Renders the candidate block. {@code capsByAgent} maps {@code agentId → capabilities}
     * (DR-FR-7); pass an empty map when capability lookup isn't available to the caller.
     */
    public static String renderCandidates(List<AgentDefinition> candidates,
                                           Map<String, List<String>> capsByAgent) {
        Map<String, List<String>> caps = (capsByAgent == null) ? Map.of() : capsByAgent;
        return candidates.stream()
                .map(a -> {
                    List<String> agentCaps = caps.get(a.id());
                    String capabilities = (agentCaps != null && !agentCaps.isEmpty())
                            ? " [Capabilities: " + String.join(", ", agentCaps) + "]"
                            : "";
                    String tools = (a.tools() != null && !a.tools().isEmpty())
                            ? " [Tools: " + String.join(", ", a.tools()) + "]"
                            : "";
                    return String.format("- %s (ID: %s): %s%s%s",
                            a.name(), a.id(), a.description(), capabilities, tools);
                })
                .collect(Collectors.joining("\n"));
    }
}
