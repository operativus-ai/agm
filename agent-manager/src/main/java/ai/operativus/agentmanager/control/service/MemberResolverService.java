package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.model.MemberResolverType;
import ai.operativus.agentmanager.core.model.RunOptions;
import ai.operativus.agentmanager.core.registry.MemberResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Resolves a team's effective member roster at run time by
 *     dispatching on {@code agents.member_resolver_type}. Implements
 *     {@link MemberResolver} so the orchestrator depends on the SPI seam, not on
 *     this concrete class.
 *
 *     <p>Strategy table:
 *     <ul>
 *       <li>{@link MemberResolverType#STATIC} — returns {@code agents.members}
 *           verbatim. Production-default path.</li>
 *       <li>{@link MemberResolverType#ORG_TIER} — stub in v1; logs at DEBUG and
 *           returns the static list. Replaced by a real tier-filtered roster once
 *           org-tier metadata flows through the request context.</li>
 *       <li>{@link MemberResolverType#FEATURE_FLAG} — stub in v1; same logging
 *           behavior as ORG_TIER. Real per-member feature gating is a follow-up.</li>
 *     </ul>
 *
 *     <p>Cross-tenant defense: when the team agent is not found for the caller's
 *     {@code orgId}, returns an empty list rather than throwing. The orchestrator
 *     surfaces this as "no members configured" instead of leaking a 4xx through
 *     what should be a transparent resolution.
 *
 *     <p>Per-run caching is intentionally NOT here — the orchestrator memoizes per
 *     run (it owns the run-lifecycle boundary). This service is stateless so the
 *     same instance is safe under virtual-thread concurrency.
 * State: Stateless
 */
@Service
public class MemberResolverService implements MemberResolver {

    private static final Logger log = LoggerFactory.getLogger(MemberResolverService.class);

    private final AgentRepository agentRepository;

    public MemberResolverService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public List<String> resolveMembers(String teamAgentId, String orgId, String userId, RunOptions options) {
        if (teamAgentId == null || teamAgentId.isBlank() || orgId == null || orgId.isBlank()) {
            return List.of();
        }
        Optional<AgentEntity> teamOpt = agentRepository.findByIdAndOrgId(teamAgentId, orgId);
        if (teamOpt.isEmpty()) {
            // Cross-tenant or unknown — return empty so the orchestrator path is
            // transparent. The caller already validated the team agent existed
            // before invoking us; this guard exists to prevent NPEs under races.
            log.warn("resolveMembers: team agent {} not found in org {} — returning empty roster", teamAgentId, orgId);
            return List.of();
        }
        AgentEntity team = teamOpt.get();
        List<String> staticRoster = team.getMembers() != null ? team.getMembers() : List.of();
        MemberResolverType type = parseType(team.getMemberResolverType());

        return switch (type) {
            case STATIC -> staticRoster;
            case ORG_TIER -> {
                log.debug("ORG_TIER resolver requested for team {} (org {}, user {}) — stub returning static roster",
                        teamAgentId, orgId, userId);
                yield staticRoster;
            }
            case FEATURE_FLAG -> {
                log.debug("FEATURE_FLAG resolver requested for team {} (org {}, user {}) — stub returning static roster",
                        teamAgentId, orgId, userId);
                yield staticRoster;
            }
        };
    }

    /**
     * Defensive parse of {@code agents.member_resolver_type} string. Unknown / null /
     * blank values fall back to STATIC so a hand-edited DB row that drifts off the
     * enum doesn't crash team orchestration.
     */
    private static MemberResolverType parseType(String raw) {
        if (raw == null || raw.isBlank()) return MemberResolverType.STATIC;
        try {
            return MemberResolverType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown member_resolver_type '{}' on agent row — defaulting to STATIC", raw);
            return MemberResolverType.STATIC;
        }
    }
}
