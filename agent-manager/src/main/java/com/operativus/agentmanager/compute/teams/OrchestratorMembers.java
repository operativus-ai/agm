package com.operativus.agentmanager.compute.teams;

import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.MemberResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain Responsibility: Resolve a team's declared member ids into the subset of
 *   {@link AgentDefinition}s eligible for dispatch — the agent exists in the
 *   registry, is {@code active()}, is not in {@code maintenanceMode()}, and is
 *   not the root agent itself.
 *
 *   <p>REQ-DR-2: when {@code agm.member-resolver.enabled=true} and a
 *   {@link MemberResolver} bean is present, the member-id source is the resolver
 *   rather than {@code rootAgent.members()}. With flag off (production default)
 *   or no resolver bean, the source is {@code rootAgent.members()} verbatim — the
 *   flag-off path is byte-identical to the pre-REQ-DR-2 baseline.
 * State: Stateless (Spring component; same resolution logic, dependency on the
 *   MemberResolver SPI is Optional so the bean works whether the flag is on or off).
 *
 * <p>Currently consumed by {@link SequentialOrchestrator}. Router, Coordinator,
 *   and Swarm apply the same predicate inline; converging them here is the
 *   follow-up to fully meet REQ-DR-2's "MemberResolver replaces static members()"
 *   contract across all orchestration strategies.
 */
@Component
public class OrchestratorMembers {

    private final Optional<MemberResolver> memberResolver;
    private final boolean memberResolverEnabled;

    public OrchestratorMembers(Optional<MemberResolver> memberResolver,
                               @Value("${agm.member-resolver.enabled:false}") boolean memberResolverEnabled) {
        this.memberResolver = memberResolver;
        this.memberResolverEnabled = memberResolverEnabled;
    }

    /**
     * Flag-aware resolution. Returns the filtered {@link AgentDefinition} list for
     * dispatch. The {@code userId} / {@code options} parameters are forwarded to the
     * resolver SPI when active; ignored when the static path runs.
     */
    public List<AgentDefinition> resolveActive(AgentDefinition rootAgent, AgentRegistry registry,
                                               String orgId, String userId, RunOptions options) {
        List<String> memberIds = memberSourceIds(rootAgent, orgId, userId, options);
        return filterAndMap(memberIds, rootAgent, registry, orgId);
    }

    /**
     * Back-compat static entry point. Bypasses the resolver SPI entirely — callers
     * that don't yet have userId/options threaded through can keep using this and
     * the byte-identical pre-REQ-DR-2 behavior runs. New callers should prefer the
     * instance method to participate in flag-on resolution.
     */
    public static List<AgentDefinition> resolveActive(AgentDefinition rootAgent, AgentRegistry registry, String orgId) {
        if (rootAgent.members() == null) {
            return List.of();
        }
        return filterAndMap(rootAgent.members(), rootAgent, registry, orgId);
    }

    private List<String> memberSourceIds(AgentDefinition rootAgent, String orgId, String userId, RunOptions options) {
        if (memberResolverEnabled && memberResolver.isPresent()) {
            return memberResolver.get().resolveMembers(rootAgent.id(), orgId, userId, options);
        }
        return rootAgent.members() != null ? rootAgent.members() : List.of();
    }

    private static List<AgentDefinition> filterAndMap(List<String> memberIds, AgentDefinition rootAgent,
                                                       AgentRegistry registry, String orgId) {
        if (memberIds == null || memberIds.isEmpty()) {
            return List.of();
        }
        return memberIds.stream()
                .map(id -> registry.findById(id, orgId))
                .filter(Objects::nonNull)
                .filter(a -> a.active() && !a.maintenanceMode())
                .filter(a -> !a.id().equals(rootAgent.id()))
                .toList();
    }
}
