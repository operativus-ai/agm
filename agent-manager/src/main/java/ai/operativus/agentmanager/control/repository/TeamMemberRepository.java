package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for the Join Table representing Agents assigned to Teams.
 * State: Stateless
 */
@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMember.TeamMemberId> {
    
    /**
     * @summary Retrieves a list of all agent memberships for a specific team.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<TeamMember> findByTeamId(String teamId);

    /**
     * @summary Retrieves a list of all team memberships for a specific agent.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<TeamMember> findByAgentId(String agentId);

    /**
     * @summary Unconditionally removes all agent memberships associated with a specific team ID.
     * @logic Derived query mapped to a delete execution by Spring Data JPA framework.
     */
    void deleteByTeamId(String teamId);
}
