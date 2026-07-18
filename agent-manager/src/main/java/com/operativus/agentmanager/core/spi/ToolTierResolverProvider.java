package com.operativus.agentmanager.core.spi;

import com.operativus.agentmanager.core.model.DecisionPackage;

import java.util.Optional;

/**
 * Service Provider Interface (SPI) for runtime-extensible HITL tier resolution.
 *
 * <p>Implementations resolve a tool name to a {@link DecisionPackage.DecisionTier} when their
 * domain owns the tool's tier policy (e.g., a Composio adapter's resolver owns the policy for
 * tool names with the {@code composio_*} prefix). {@code HitlAdvisor} consults all registered
 * providers in declared order and uses the first non-empty resolution; if every provider
 * returns {@link Optional#empty()}, {@code HitlAdvisor} falls through to its built-in static
 * sets ({@code DESTRUCTIVE_TOOLS}, {@code FINOPS_TOOLS}).</p>
 *
 * <p>This SPI exists to prevent reverse package coupling: {@code compute/advisor/} (where
 * {@code HitlAdvisor} lives) must not depend on {@code compute/tools/composio/} or any other
 * concrete tool family. Both sides depend only on this {@code core/spi/} contract.</p>
 *
 * <p>Per agm-agentos-tool-parity-impl.md §4 architectural-decisions row + audit Finding 7.</p>
 *
 * <p>Implementations are Spring beans; {@code HitlAdvisor} autowires {@code List<} of all
 * registered providers.</p>
 */
public interface ToolTierResolverProvider {

    /**
     * Resolve a HITL tier for the given tool name.
     *
     * @param toolName the registered name of the tool the LLM is attempting to invoke
     * @return the resolved tier if this provider owns the policy for that tool, or
     *     {@link Optional#empty()} if the provider does not recognize the tool
     *     (so {@code HitlAdvisor} can consult the next provider or fall through)
     */
    Optional<DecisionPackage.DecisionTier> resolveTier(String toolName);
}
