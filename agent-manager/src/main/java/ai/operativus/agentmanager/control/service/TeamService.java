package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.Team;
import ai.operativus.agentmanager.core.entity.TeamMember;
import ai.operativus.agentmanager.core.entity.TransitionEdge;
import ai.operativus.agentmanager.core.model.*;
import ai.operativus.agentmanager.control.finops.service.DailySpendService;
import ai.operativus.agentmanager.control.repository.TeamMemberRepository;
import ai.operativus.agentmanager.control.repository.TeamRepository;
import ai.operativus.agentmanager.control.repository.TransitionEdgeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import ai.operativus.agentmanager.core.registry.TeamOperations;

/**
 * Domain Responsibility: Manages the configuration, membership, edges, and modes of multi-agent Teams.
 * State: Stateless
 */
@Service
public class TeamService implements TeamOperations {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TransitionEdgeRepository transitionEdgeRepository;
    private final EntityManager entityManager;
    private final DailySpendService dailySpendService;

    public TeamService(TeamRepository teamRepository,
                       TeamMemberRepository teamMemberRepository,
                       TransitionEdgeRepository transitionEdgeRepository,
                       EntityManager entityManager,
                       DailySpendService dailySpendService) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.transitionEdgeRepository = transitionEdgeRepository;
        this.entityManager = entityManager;
        this.dailySpendService = dailySpendService;
    }

    // ── List & Query ────────────────────────────────────────────

    public List<TeamDTO> getAllTeams() {
        return teamRepository.findAllByOrgId(callerOrgId(), Pageable.unpaged()).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public Page<TeamDTO> getAllTeams(Pageable pageable) {
        return teamRepository.findAllByOrgId(callerOrgId(), pageable).map(this::toDtoEnriched);
    }

    /**
     * Paginated list with server-side search and archived filtering, scoped to caller's org.
     * Returns enriched DTOs with member counts and leader agent name.
     */
    public Page<TeamDTO> searchTeams(String search, boolean includeArchived, Pageable pageable) {
        String orgId = callerOrgId();
        Page<Team> page;
        if (search != null && !search.isBlank()) {
            if (includeArchived) {
                page = teamRepository.findByOrgIdAndSearch(orgId, search, pageable);
            } else {
                page = teamRepository.findByArchivedAndOrgIdAndSearch(false, orgId, search, pageable);
            }
        } else {
            if (includeArchived) {
                page = teamRepository.findAllByOrgId(orgId, pageable);
            } else {
                page = teamRepository.findByArchivedAndOrgId(false, orgId, pageable);
            }
        }
        return page.map(this::toDtoEnriched);
    }

    public Optional<TeamDTO> getTeamById(String id) {
        return teamRepository.findByIdAndOrgId(id, callerOrgId()).map(this::toDto);
    }

    // ── CRUD ────────────────────────────────────────────────────

    @Transactional
    public TeamDTO createTeam(TeamDTO teamDTO) {
        String id = teamDTO.id() != null ? teamDTO.id() : UUID.randomUUID().toString();
        Team team = new Team(id, teamDTO.name(), teamDTO.description(), teamDTO.teamMode(),
                teamDTO.leaderId(), teamDTO.modelId(), teamDTO.instructions(),
                teamDTO.contextWindowSize(), teamDTO.memoryEnabled(), teamDTO.addHistoryToMessages(),
                teamDTO.tools());
        team.setOrgId(callerOrgId());
        team.setHumanLead(teamDTO.humanLead());
        team.setMaxDailySpend(teamDTO.maxDailySpend());
        team.setMinSpendingAuthority(teamDTO.minSpendingAuthority());
        team.setArchived(teamDTO.archived() != null ? teamDTO.archived() : false);
        // §9 MEM-2: setIsolateMemory accepts null and normalises to false (entity contract).
        team.setIsolateMemory(teamDTO.isolateMemory());
        Team savedTeam = teamRepository.save(team);
        return toDto(savedTeam);
    }

    @Transactional
    public TeamDTO updateTeam(String id, TeamDTO teamDTO) {
        return teamRepository.findByIdAndOrgId(id, callerOrgId()).map(existing -> {
            Optional.ofNullable(teamDTO.name()).ifPresent(existing::setName);
            Optional.ofNullable(teamDTO.description()).ifPresent(existing::setDescription);
            Optional.ofNullable(teamDTO.teamMode()).ifPresent(existing::setTeamMode);
            Optional.ofNullable(teamDTO.leaderId()).ifPresent(existing::setLeaderId);
            Optional.ofNullable(teamDTO.modelId()).ifPresent(existing::setModelId);
            Optional.ofNullable(teamDTO.instructions()).ifPresent(existing::setInstructions);
            Optional.ofNullable(teamDTO.contextWindowSize()).ifPresent(existing::setContextWindowSize);
            Optional.ofNullable(teamDTO.memoryEnabled()).ifPresent(existing::setMemoryEnabled);
            Optional.ofNullable(teamDTO.addHistoryToMessages()).ifPresent(existing::setAddHistoryToMessages);
            // §9 MEM-2: PUT update follows the established null-as-keep semantics — only writes
            // when the inbound DTO carries an explicit value. Idempotent re-PUT preserves prior state.
            Optional.ofNullable(teamDTO.isolateMemory()).ifPresent(existing::setIsolateMemory);
            Optional.ofNullable(teamDTO.tools()).ifPresent(existing::setTools);
            Optional.ofNullable(teamDTO.humanLead()).ifPresent(existing::setHumanLead);
            Optional.ofNullable(teamDTO.maxDailySpend()).ifPresent(existing::setMaxDailySpend);
            Optional.ofNullable(teamDTO.minSpendingAuthority()).ifPresent(existing::setMinSpendingAuthority);
            Optional.ofNullable(teamDTO.archived()).ifPresent(existing::setArchived);
            Team updated = teamRepository.save(existing);
            return toDto(updated);
        }).orElseThrow(() -> new IllegalArgumentException("Team not found for ID: " + id));
    }

    @Transactional
    public void deleteTeam(String id) {
        if (teamRepository.existsByIdAndOrgId(id, callerOrgId())) {
            teamRepository.deleteById(id);
        }
    }

    // ── Archive / Restore ───────────────────────────────────────

    @Transactional
    public TeamDTO archiveTeam(String id) {
        return teamRepository.findByIdAndOrgId(id, callerOrgId()).map(team -> {
            team.setArchived(true);
            return toDto(teamRepository.save(team));
        }).orElseThrow(() -> new IllegalArgumentException("Team not found for ID: " + id));
    }

    @Transactional
    public TeamDTO restoreTeam(String id) {
        return teamRepository.findByIdAndOrgId(id, callerOrgId()).map(team -> {
            team.setArchived(false);
            return toDto(teamRepository.save(team));
        }).orElseThrow(() -> new IllegalArgumentException("Team not found for ID: " + id));
    }

    // ── Members ─────────────────────────────────────────────────

    public List<TeamMemberDTO> getTeamMembers(String teamId) {
        // Cross-tenant: parent team is invisible → child members invisible.
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            return List.of();
        }
        return teamMemberRepository.findByTeamId(teamId).stream()
                .map(this::toMemberDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public TeamMemberDTO addTeamMember(String teamId, TeamMemberDTO request) {
        // Cross-tenant: same shape as missing-team. Controller maps IllegalArgumentException
        // here to 400 (legacy contract), not 404 — isolation goal is "no cross-tenant write,"
        // not response-shape unification.
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            throw new IllegalArgumentException("Team not found for ID: " + teamId);
        }
        // Validate role
        NodeRole.fromString(request.role());
        TeamMember member = new TeamMember(teamId, request.agentId(), request.role());
        TeamMember saved = teamMemberRepository.save(member);
        return toMemberDto(saved);
    }

    @Transactional
    public void removeTeamMember(String teamId, String agentId) {
        // Cross-tenant: silently no-op. Controller still returns 204 unconditionally.
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            return;
        }
        TeamMember.TeamMemberId id = new TeamMember.TeamMemberId(teamId, agentId);
        teamMemberRepository.deleteById(id);
    }

    /**
     * Atomically adds multiple members to a team. Validates all roles before persisting.
     * Rolls back entire batch on any validation failure.
     */
    @Transactional
    public List<TeamMemberDTO> bulkAddMembers(String teamId, BulkMemberRequest request) {
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            throw new IllegalArgumentException("Team not found for ID: " + teamId);
        }
        if (request.members() == null || request.members().isEmpty()) {
            return List.of();
        }

        // Validate all roles upfront before any persistence
        for (BulkMemberRequest.MemberEntry entry : request.members()) {
            NodeRole.fromString(entry.role());
            if (entry.agentId() == null || entry.agentId().isBlank()) {
                throw new IllegalArgumentException("agentId is required for all members");
            }
        }

        List<TeamMemberDTO> results = new ArrayList<>();
        for (BulkMemberRequest.MemberEntry entry : request.members()) {
            TeamMember member = new TeamMember(teamId, entry.agentId(), entry.role());
            TeamMember saved = teamMemberRepository.save(member);
            results.add(toMemberDto(saved));
        }
        return results;
    }

    // ── Transition Edges (DAG) ──────────────────────────────────

    public List<TransitionEdge> getTransitionEdges(String teamId) {
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            return List.of();
        }
        return transitionEdgeRepository.findByTeamId(teamId);
    }

    /**
     * Adds a DAG transition edge. Checks for duplicates before persisting.
     * @throws IllegalStateException if the edge already exists (caller should map to 409)
     * @throws IllegalArgumentException if the team is missing or in another tenant
     */
    @Transactional
    public TransitionEdge addTransitionEdge(String teamId, String sourceAgentId, String targetAgentId) {
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            throw new IllegalArgumentException("Team not found for ID: " + teamId);
        }
        if (transitionEdgeRepository.existsByTeamIdAndSourceAgentIdAndTargetAgentId(teamId, sourceAgentId, targetAgentId)) {
            throw new IllegalStateException(
                    "Transition edge already exists: " + sourceAgentId + " → " + targetAgentId + " in team " + teamId);
        }
        TransitionEdge edge = new TransitionEdge(UUID.randomUUID().toString(), teamId, sourceAgentId, targetAgentId);
        return transitionEdgeRepository.save(edge);
    }

    @Transactional
    public void removeTransitionEdge(String teamId, String edgeId) {
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            return;
        }
        transitionEdgeRepository.deleteById(edgeId);
    }

    // ── Clone ───────────────────────────────────────────────────

    /**
     * Deep-copies a team including members, transition edges, and manifest fields.
     * Entire operation is atomic.
     */
    @Transactional
    public TeamDTO cloneTeam(String sourceId) {
        Team source = teamRepository.findByIdAndOrgId(sourceId, callerOrgId())
                .orElseThrow(() -> new IllegalArgumentException("Team not found for ID: " + sourceId));

        String newId = UUID.randomUUID().toString();
        Team clone = new Team(
                newId,
                source.getName() + " (Clone)",
                source.getDescription(),
                source.getTeamMode(),
                source.getLeaderId(),
                source.getModelId(),
                source.getInstructions(),
                source.getContextWindowSize(),
                source.getMemoryEnabled(),
                source.getAddHistoryToMessages(),
                source.getTools() != null ? new ArrayList<>(source.getTools()) : null
        );
        clone.setOrgId(callerOrgId());
        clone.setHumanLead(source.getHumanLead());
        clone.setMaxDailySpend(source.getMaxDailySpend());
        clone.setMinSpendingAuthority(source.getMinSpendingAuthority());
        clone.setArchived(false);
        teamRepository.save(clone);

        // Clone members
        List<TeamMember> sourceMembers = teamMemberRepository.findByTeamId(sourceId);
        for (TeamMember m : sourceMembers) {
            teamMemberRepository.save(new TeamMember(newId, m.getAgentId(), m.getRole()));
        }

        // Clone transition edges
        List<TransitionEdge> sourceEdges = transitionEdgeRepository.findByTeamId(sourceId);
        for (TransitionEdge e : sourceEdges) {
            transitionEdgeRepository.save(new TransitionEdge(
                    UUID.randomUUID().toString(), newId, e.getSourceAgentId(), e.getTargetAgentId()));
        }

        return toDto(clone);
    }

    // ── Health ──────────────────────────────────────────────────

    /**
     * Builds an aggregated health summary for a single team.
     * Uses JPA EntityManager for the agent name/active lookup to avoid circular dependency with AgentService.
     */
    public TeamHealthDTO getTeamHealth(String teamId) {
        Team team = teamRepository.findByIdAndOrgId(teamId, callerOrgId())
                .orElseThrow(() -> new IllegalArgumentException("Team not found for ID: " + teamId));

        List<TeamMember> members = teamMemberRepository.findByTeamId(teamId);
        int edgeCount = (int) transitionEdgeRepository.countByTeamId(teamId);

        // Count active vs inactive members via agent entity lookup
        int activeMemberCount = 0;
        for (TeamMember m : members) {
            AgentEntity agent = entityManager.find(AgentEntity.class, m.getAgentId());
            if (agent != null && Boolean.TRUE.equals(agent.isActive())) {
                activeMemberCount++;
            }
        }

        // Resolve leader info
        TeamHealthDTO.LeaderInfo leaderInfo = null;
        if (team.getLeaderId() != null && !team.getLeaderId().isBlank()) {
            AgentEntity leader = entityManager.find(AgentEntity.class, team.getLeaderId());
            if (leader != null) {
                leaderInfo = new TeamHealthDTO.LeaderInfo(
                        leader.getId(), leader.getName(), Boolean.TRUE.equals(leader.isActive()));
            }
        }

        List<String> memberAgentIds = members.stream().map(TeamMember::getAgentId).toList();
        double currentDailySpend = dailySpendService.currentTeamDailySpendUsd(memberAgentIds);

        return new TeamHealthDTO(
                teamId,
                members.size(),
                activeMemberCount,
                members.size() - activeMemberCount,
                leaderInfo,
                edgeCount,
                currentDailySpend,
                team.getMaxDailySpend() != null ? team.getMaxDailySpend() : 0.0
        );
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return orgId != null ? orgId : "DEFAULT_SYSTEM_ORG";
    }

    // ── DTO Mapping ─────────────────────────────────────────────

    private TeamDTO toDto(Team team) {
        return new TeamDTO(
                team.getId(),
                team.getName(),
                team.getDescription(),
                team.getTeamMode(),
                team.getLeaderId(),
                team.getModelId(),
                team.getInstructions(),
                team.getContextWindowSize(),
                team.getMemoryEnabled(),
                team.getAddHistoryToMessages(),
                team.getIsolateMemory(),
                team.getTools(),
                team.getHumanLead(),
                team.getMaxDailySpend(),
                team.getMinSpendingAuthority(),
                team.getArchived(),
                null, // memberCount — not enriched in single-entity fetch
                null, // activeMemberCount
                null, // leaderAgentName
                team.getCreatedAt() != null ? team.getCreatedAt() : LocalDateTime.now(),
                team.getUpdatedAt() != null ? team.getUpdatedAt() : LocalDateTime.now()
        );
    }

    /**
     * Enriched DTO for list queries — includes member count and leader agent name.
     */
    private TeamDTO toDtoEnriched(Team team) {
        List<TeamMember> members = teamMemberRepository.findByTeamId(team.getId());

        int activeMemberCount = 0;
        for (TeamMember m : members) {
            AgentEntity agent = entityManager.find(AgentEntity.class, m.getAgentId());
            if (agent != null && Boolean.TRUE.equals(agent.isActive())) {
                activeMemberCount++;
            }
        }

        String leaderName = null;
        if (team.getLeaderId() != null && !team.getLeaderId().isBlank()) {
            AgentEntity leader = entityManager.find(AgentEntity.class, team.getLeaderId());
            if (leader != null) {
                leaderName = leader.getName();
            }
        }

        return new TeamDTO(
                team.getId(),
                team.getName(),
                team.getDescription(),
                team.getTeamMode(),
                team.getLeaderId(),
                team.getModelId(),
                team.getInstructions(),
                team.getContextWindowSize(),
                team.getMemoryEnabled(),
                team.getAddHistoryToMessages(),
                team.getIsolateMemory(),
                team.getTools(),
                team.getHumanLead(),
                team.getMaxDailySpend(),
                team.getMinSpendingAuthority(),
                team.getArchived(),
                members.size(),
                activeMemberCount,
                leaderName,
                team.getCreatedAt() != null ? team.getCreatedAt() : LocalDateTime.now(),
                team.getUpdatedAt() != null ? team.getUpdatedAt() : LocalDateTime.now()
        );
    }

    private TeamMemberDTO toMemberDto(TeamMember member) {
        return new TeamMemberDTO(
                member.getTeamId(),
                member.getAgentId(),
                member.getRole(),
                member.getHumanReview(),
                member.getJoinedAt() != null ? member.getJoinedAt() : LocalDateTime.now()
        );
    }
}
