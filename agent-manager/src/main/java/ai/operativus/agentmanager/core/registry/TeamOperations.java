package ai.operativus.agentmanager.core.registry;

import ai.operativus.agentmanager.core.entity.TransitionEdge;
import ai.operativus.agentmanager.core.model.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Registry contract to access Team operations from the Control plane.
 */
public interface TeamOperations {

    // List & Query
    List<TeamDTO> getAllTeams();
    Page<TeamDTO> getAllTeams(Pageable pageable);
    Page<TeamDTO> searchTeams(String search, boolean includeArchived, Pageable pageable);
    Optional<TeamDTO> getTeamById(String id);

    // CRUD
    TeamDTO createTeam(TeamDTO teamDTO);
    TeamDTO updateTeam(String id, TeamDTO teamDTO);
    void deleteTeam(String id);

    // Archive / Restore
    TeamDTO archiveTeam(String id);
    TeamDTO restoreTeam(String id);

    // Members
    List<TeamMemberDTO> getTeamMembers(String teamId);
    TeamMemberDTO addTeamMember(String teamId, TeamMemberDTO request);
    void removeTeamMember(String teamId, String agentId);
    List<TeamMemberDTO> bulkAddMembers(String teamId, BulkMemberRequest request);

    // Transition Edges (DAG)
    List<TransitionEdge> getTransitionEdges(String teamId);
    TransitionEdge addTransitionEdge(String teamId, String sourceAgentId, String targetAgentId);
    void removeTransitionEdge(String teamId, String edgeId);

    // Clone
    TeamDTO cloneTeam(String sourceId);

    // Health
    TeamHealthDTO getTeamHealth(String teamId);
}
