package ai.operativus.agentmanager.core.model.definitions;

import java.util.List;

/**
 * Domain Responsibility: Defines the interface for retrieving agent and team definitions, providing a unified abstraction over underlying storage mechanisms.
 *
 * <p><b>Tenant scoping is explicit.</b> Both methods take {@code orgId} as a parameter so the
 * implementation does not have to read it from a thread-local / ScopedValue. This keeps the
 * registry safe to call from cross-thread fan-outs (virtual threads, reactor schedulers,
 * {@code @Async} executors) where {@link ai.operativus.agentmanager.core.callback.AgentContextHolder}
 * may not be bound. Callers must resolve {@code orgId} on the caller-bound thread before any hop.</p>
 *
 * State: Stateless (Interface)
 */
public interface AgentRegistry {

    /**
     * Finds an agent definition by its ID, scoped to the supplied organization.
     *
     * @param agentId  the agent identifier to look up
     * @param orgId    the organization scope; if null, implementations fall back to the system-default org
     * @return the matching {@link AgentDefinition}, or {@code null} if no agent or team with that id exists in the org
     */
    AgentDefinition findById(String agentId, String orgId);

    /**
     * Returns a list of all available agent definitions in the supplied organization, optionally including inactive ones.
     *
     * @param includeInactive  whether to include agents flagged inactive
     * @param orgId            the organization scope; if null, implementations fall back to the system-default org
     */
    List<AgentDefinition> findAll(boolean includeInactive, String orgId);
}
