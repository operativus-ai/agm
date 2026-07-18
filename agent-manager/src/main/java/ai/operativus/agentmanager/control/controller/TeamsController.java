package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.TransitionEdge;
import ai.operativus.agentmanager.core.model.*;
import ai.operativus.agentmanager.control.repository.TeamRepository;
import ai.operativus.agentmanager.core.registry.TeamOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Domain Responsibility: Exposes REST APIs for manipulating multi-agent Teams,
 * their member relationships, DAG transition edge constraints, and operational health.
 * State: Stateless
 * Dependencies: TeamOperations (service layer)
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamsController {

    private final TeamOperations teamService;
    private final TeamRepository teamRepository;

    public TeamsController(TeamOperations teamService, TeamRepository teamRepository) {
        this.teamService = teamService;
        this.teamRepository = teamRepository;
    }

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return orgId != null ? orgId : "DEFAULT_SYSTEM_ORG";
    }

    // ── List & Search ───────────────────────────────────────────

    /**
     * Paginated teams list with optional server-side search and archived filter.
     * @param search  Optional text search across name and description
     * @param showArchived  If true, includes archived teams (default: false)
     */
    @GetMapping
    public ResponseEntity<Page<TeamDTO>> listTeams(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "false") boolean showArchived,
            @org.springdoc.core.annotations.ParameterObject Pageable pageable) {
        return ResponseEntity.ok(teamService.searchTeams(search, showArchived, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TeamDTO> getTeam(@PathVariable("id") String id) {
        return teamService.getTeamById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── CRUD ────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<TeamDTO> createTeam(@RequestBody TeamDTO teamDTO) {
        TeamDTO created = teamService.createTeam(teamDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TeamDTO> updateTeam(@PathVariable("id") String id, @RequestBody TeamDTO teamDTO) {
        try {
            TeamDTO updated = teamService.updateTeam(id, teamDTO);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTeam(@PathVariable("id") String id) {
        teamService.deleteTeam(id);
        return ResponseEntity.noContent().build();
    }

    // ── Archive / Restore ───────────────────────────────────────

    @PatchMapping("/{id}/archive")
    public ResponseEntity<TeamDTO> archiveTeam(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(teamService.archiveTeam(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/restore")
    public ResponseEntity<TeamDTO> restoreTeam(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(teamService.restoreTeam(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Clone ───────────────────────────────────────────────────

    @PostMapping("/{id}/clone")
    public ResponseEntity<TeamDTO> cloneTeam(@PathVariable("id") String id) {
        try {
            TeamDTO cloned = teamService.cloneTeam(id);
            return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Health ──────────────────────────────────────────────────

    @GetMapping("/{id}/health")
    public ResponseEntity<TeamHealthDTO> getTeamHealth(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(teamService.getTeamHealth(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Members ─────────────────────────────────────────────────

    @GetMapping("/{id}/members")
    public ResponseEntity<List<TeamMemberDTO>> getTeamMembers(@PathVariable("id") String id) {
        if (!teamRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(teamService.getTeamMembers(id));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<TeamMemberDTO> addTeamMember(
            @PathVariable("id") String id, @RequestBody TeamMemberDTO memberDTO) {
        if (!teamRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        try {
            TeamMemberDTO added = teamService.addTeamMember(id, memberDTO);
            return ResponseEntity.status(HttpStatus.CREATED).body(added);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}/members/{agentId}")
    public ResponseEntity<Void> removeTeamMember(
            @PathVariable("id") String id, @PathVariable("agentId") String agentId) {
        if (teamRepository.existsByIdAndOrgId(id, callerOrgId())) {
            teamService.removeTeamMember(id, agentId);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members/batch")
    public ResponseEntity<?> bulkAddMembers(
            @PathVariable("id") String id, @RequestBody BulkMemberRequest request) {
        if (!teamRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        try {
            List<TeamMemberDTO> added = teamService.bulkAddMembers(id, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(added);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // ── DAG Transition Edges ────────────────────────────────────

    @GetMapping("/{id}/edges")
    public ResponseEntity<List<TransitionEdge>> getTransitionEdges(@PathVariable("id") String id) {
        if (!teamRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(teamService.getTransitionEdges(id));
    }

    @PostMapping("/{id}/edges")
    public ResponseEntity<?> addTransitionEdge(
            @PathVariable("id") String teamId,
            @RequestBody TransitionEdgeRequest request) {
        if (!teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        try {
            TransitionEdge saved = teamService.addTransitionEdge(teamId, request.sourceAgentId(), request.targetAgentId());
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalStateException e) {
            // Duplicate edge — 409 Conflict
            return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{teamId}/edges/{edgeId}")
    public ResponseEntity<Void> removeTransitionEdge(
            @PathVariable("teamId") String teamId,
            @PathVariable("edgeId") String edgeId) {
        if (teamRepository.existsByIdAndOrgId(teamId, callerOrgId())) {
            teamService.removeTransitionEdge(teamId, edgeId);
        }
        return ResponseEntity.noContent().build();
    }

    public record TransitionEdgeRequest(String sourceAgentId, String targetAgentId) {}

    // ── FinOps & Manifest ───────────────────────────────────────

    @GetMapping("/manifests")
    public ResponseEntity<List<TeamManifestDTO>> listTeamManifests() {
        List<TeamManifestDTO> manifests = teamService.getAllTeams().stream()
                .map(t -> new TeamManifestDTO(
                        t.id(),
                        t.humanLead(),
                        t.maxDailySpend() != null ? t.maxDailySpend() : 0.0,
                        t.minSpendingAuthority() != null ? t.minSpendingAuthority() : 0.0,
                        null,
                        null
                ))
                .toList();
        return ResponseEntity.ok(manifests);
    }

    @PatchMapping("/{id}/manifest")
    public ResponseEntity<TeamManifestDTO> updateTeamManifest(
            @PathVariable("id") String id, @RequestBody TeamManifestDTO manifest) {
        try {
            TeamDTO updates = new TeamDTO(null, null, null, null, null, null, null, null, null, null, null, null,
                    manifest.humanLead(), manifest.maxDailySpend(), manifest.minSpendingAuthority(),
                    null, null, null, null, null, null);
            TeamDTO updated = teamService.updateTeam(id, updates);
            TeamManifestDTO result = new TeamManifestDTO(
                    updated.id(),
                    updated.humanLead(),
                    updated.maxDailySpend() != null ? updated.maxDailySpend() : 0.0,
                    updated.minSpendingAuthority() != null ? updated.minSpendingAuthority() : 0.0,
                    null,
                    null
            );
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
