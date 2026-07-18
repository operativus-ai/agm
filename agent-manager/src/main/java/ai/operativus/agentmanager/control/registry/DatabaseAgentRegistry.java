package ai.operativus.agentmanager.control.registry;

import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.Team;
import ai.operativus.agentmanager.core.entity.TeamMember;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.control.repository.TeamMemberRepository;
import ai.operativus.agentmanager.control.repository.TeamRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Implements the AgentRegistry interface, providing caching and bridging database entities (AgentEntity, Team) into the unified AgentDefinition records.
 *
 * <p>Cache key policy (§27.10 — multi-tenant isolation): both {@code agents} and
 * {@code allAgents} caches key on the explicit {@code orgId} parameter in addition to the
 * functional arguments. This prevents cross-tenant cache reads when the same agentId /
 * includeInactive flag is served to callers in different organizations. When the parameter is
 * null (unauthenticated boot priming, super-admin contexts), the literal {@code 'no-org'} is
 * used so the cache is still populated but kept separate from any tenant-scoped entry.</p>
 *
 * <p>The cache key derives from the parameter (not from {@code AgentContextHolder}) so the
 * registry is safe to invoke from cross-thread fan-outs where {@code AgentContextHolder} may
 * not be bound. See {@link AgentRegistry} Javadoc for the contract.</p>
 *
 * State: Stateful (Manages Spring Cache abstraction)
 */
@Service
public class DatabaseAgentRegistry implements AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAgentRegistry.class);
    private final AgentRepository agentRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    public DatabaseAgentRegistry(AgentRepository agentRepository, TeamRepository teamRepository, TeamMemberRepository teamMemberRepository) {
        this.agentRepository = agentRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    private static String resolveOrgId(String orgId) {
        return (orgId != null && !orgId.isBlank()) ? orgId
                : ai.operativus.agentmanager.core.model.TenantConstants.DEFAULT_SYSTEM_ORG;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "agents",
            key = "#agentId + '|' + (#orgId ?: 'no-org')",
            sync = true)
    public AgentDefinition findById(String agentId, String orgId) {
        log.info("Fetching agent configuration from database for ID: {} (orgId: {})", agentId, orgId);
        String resolvedOrgId = resolveOrgId(orgId);

        AgentDefinition adminDefinedAgent = agentRepository.findByIdAndOrgId(agentId, resolvedOrgId)
                .filter(e -> Boolean.TRUE.equals(e.isActive()))
                .map(this::mapToDefinition)
                .orElse(null);

        if (adminDefinedAgent != null) {
            return adminDefinedAgent;
        }

        log.info("Agent ID {} not found in agents table. Falling back to Teams parity check.", agentId);
        return teamRepository.findByIdAndOrgId(agentId, resolvedOrgId)
               .map(team -> {
                   List<TeamMember> members = teamMemberRepository.findByTeamId(team.getId());
                   return mapTeamToDefinition(team, members);
               }).orElse(null);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "allAgents",
            key = "#includeInactive + '|' + (#orgId ?: 'no-org')")
    public List<AgentDefinition> findAll(boolean includeInactive, String orgId) {
        log.info("Fetching all agent and team configurations from database (includeInactive: {}, orgId: {}).", includeInactive, orgId);
        List<AgentDefinition> allDefinitions = new ArrayList<>();
        String resolvedOrgId = resolveOrgId(orgId);

        // 1. Fetch Agents (Filter based on includeInactive flag)
        List<AgentEntity> agentsToMap = includeInactive
                ? agentRepository.findAllByOrgId(resolvedOrgId, org.springframework.data.domain.Pageable.unpaged()).getContent()
                : agentRepository.findByOrgIdAndActiveTrue(resolvedOrgId);
        allDefinitions.addAll(agentsToMap.stream()
                .map(this::mapToDefinition)
                .collect(Collectors.toList()));

        // 2. Fetch Teams
        allDefinitions.addAll(teamRepository.findByOrgId(resolvedOrgId).stream()
                .map(team -> {
                    List<TeamMember> members = teamMemberRepository.findByTeamId(team.getId());
                    return mapTeamToDefinition(team, members);
                })
                .collect(Collectors.toList()));

        return allDefinitions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void primeCache() {
        log.info("Priming Agent Registry Cache on application startup directly from database.");
        // Boot-time priming runs without an authenticated org context — pass null so the impl
        // routes through the system-default org bucket. Tenant-scoped entries are populated
        // lazily on the first authenticated request for that tenant.
        findAll(false, null);
    }

    private AgentDefinition mapToDefinition(AgentEntity entity) {
        return new AgentDefinition(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getInstructions(),
                entity.getModelId(),
                entity.getContextWindowSize(),
                entity.getMemoryEnabled(),
                entity.getAddHistoryToMessages(),
                safeList(entity.getTools()),
                Boolean.TRUE.equals(entity.isReasoningEnabled()),
                Boolean.TRUE.equals(entity.isTeam()),
                entity.getTeamMode(),
                safeList(entity.getMembers()),
                safeList(entity.getAllowedRoles()),
                Boolean.TRUE.equals(entity.isRequiresPiiRedaction()),
                Boolean.TRUE.equals(entity.isApprovedForProduction()),
                Boolean.TRUE.equals(entity.isMaintenanceMode()),
                Boolean.TRUE.equals(entity.isActive()),
                entity.getConfiguration(),
                entity.getMarkdownDocs(),
                entity.getSupportChannel(),
                entity.getPrimaryOwner(),
                safeList(entity.getSupportedLocales()),
                entity.getAccessibilityCompatibility(),
                safeList(entity.getTrainingDatasets()),
                safeList(entity.getKnowledgeBaseIds()),
                Boolean.TRUE.equals(entity.isEnforceJsonOutput()),
                entity.getTemperature(),
                entity.getTopP(),
                entity.getFrequencyPenalty(),
                entity.getSystemPromptMode(),
                entity.getMaxConcurrentExecutions(),
                entity.getFinOpsTokenBudget(),
                entity.getFinOpsRiskTier(),
                entity.getSecurityTier(),
                entity.getComplianceTier(),
                entity.getCompressionThreshold(),
                entity.getSummarizationThreshold(),
                entity.getOptimizationModelId(),
                entity.getPreHooks(),
                entity.getPostHooks(),
                resolveIsolateMemoryForAgentEntity(entity), // §9 MEM-2: single-agent rows always false; team-proxy rows inherit from the Team row.
                safeList(entity.getFallbackModelIds()), // gap #8 — per-agent fallback chain.
                entity.getHumanReview(), // REQ-HR follow-up — surfaces the agent's own human_review JSONB.
                entity.getCapabilities() == null ? null : java.util.Arrays.asList(entity.getCapabilities())
        );
    }

    /**
     * §9 MEM-2: when {@link #findById} resolves a team-proxy {@link AgentEntity}
     * (i.e., {@code isTeam=true}) ahead of falling back to the Team table, the
     * {@code isolateMemory} flag lives on the adjacent {@link Team} row, not on
     * the {@code AgentEntity}. Look up the Team row by the same id and inherit
     * the flag. Single-agent rows always return {@code false} — the concept
     * doesn't apply at the agent level.
     */
    private boolean resolveIsolateMemoryForAgentEntity(AgentEntity entity) {
        if (!Boolean.TRUE.equals(entity.isTeam())) {
            return false;
        }
        return teamRepository.findById(entity.getId())
                .map(t -> Boolean.TRUE.equals(t.getIsolateMemory()))
                .orElse(false);
    }

    private <T> List<T> safeList(List<T> source) {
        if (source == null) return null;
        return new ArrayList<>(source);
    }
    
    private AgentDefinition mapTeamToDefinition(Team team, List<TeamMember> members) {
        List<String> memberIds = members.stream()
                .map(TeamMember::getAgentId)
                .collect(Collectors.toList());
        
        // Inherit model semantics from the leader if one exists
        String modelId = null;
        if (team.getLeaderId() != null) {
            AgentEntity leader = agentRepository.findById(team.getLeaderId()).orElse(null);
            if (leader != null) {
                modelId = leader.getModelId();
            }
        }
        
        return new AgentDefinition(
                team.getId(),
                team.getName(),
                team.getDescription() != null ? team.getDescription() : "Auto-generated team orchestration proxy.",
                "Team orchestration proxy. Delegates tasks to members.",
                modelId,
                team.getContextWindowSize(),
                team.getMemoryEnabled(),
                team.getAddHistoryToMessages(), // Requires adding this to Team.java!
                new ArrayList<>(), // Tools are dynamically appended in AgentService based on teamMode
                false, // Reasoning
                true,  // IsTeam!
                team.getTeamMode() != null ? team.getTeamMode() : "ROUTER",
                memberIds,
                null, // Roles
                false, // PII
                true,  // Approved
                false, // Maintenance
                true,  // Active
                null,  // Config
                null,
                null,
                null,
                null,
                null,
                null,
                new ArrayList<>(), // KnowledgeBaseIds
                false, // enforceJsonOutput
                null,  // temperature
                null,  // topP
                null,  // frequencyPenalty
                null,  // systemPromptMode
                null,  // maxConcurrentExecutions
                null,  // finOpsTokenBudget
                null,  // finOpsRiskTier
                1, // securityTier default
                ai.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD, // complianceTier
                null, // compressionThreshold default
                null, // summarizationThreshold default
                null, // optimizationModelId default
                null, // preHooks
                null, // postHooks
                Boolean.TRUE.equals(team.getIsolateMemory()), // §9 MEM-2: per-team isolate-memory flag
                null, // fallbackModelIds — per-agent fallback chain; null means no per-agent override.
                null,  // humanReview — team-proxy definition has no top-level review; per-member overrides are applied by orchestrators at dispatch time.
                null   // capabilities — team-proxy definitions don't carry capability tags directly; member agents do.
        );
    }
}
