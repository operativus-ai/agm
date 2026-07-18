package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.entity.Skill;

import java.util.List;

/**
 * Domain Responsibility: Cross-module SPI seam for the Skills subsystem. Exposes
 *     ONLY the operations that {@code compute/} needs from {@code control/}:
 *     resolving the active skill set for an agent at run time so {@code SkillInjector}
 *     can augment the agent's tool list and system prompt.
 *
 *     CRUD (create / update / delete / attach / detach / paginated list) is tenant-scoped
 *     and lives exclusively on {@code SkillService} (and its admin REST controller) —
 *     it is NOT part of the cross-module contract.
 *
 *     INCLUDES semantics: {@code skill.allowedTools} is a list of tool NAMES that
 *     reference entries in {@code ToolConfig.globalToolProvider}. The skill never
 *     carries tool implementations.
 * State: Stateless contract.
 */
public interface SkillOperations {

    /**
     * Returns the active skills attached to the given agent, ordered by attachment
     * priority ASC then attachment created_at ASC. Inactive skills are filtered out.
     * The result is computed per call — no caching at this seam (the SkillInjector
     * decides whether to memoize within a single run).
     *
     * @param agentId the agent whose attached skills are being resolved
     * @return ordered list of active skills (possibly empty; never null)
     */
    List<Skill> findActiveSkillsForAgent(String agentId);
}
