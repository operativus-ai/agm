package ai.operativus.agentmanager.core.entity;

import ai.operativus.agentmanager.core.model.HumanReview;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain Responsibility: Represents the database schema and domain model for TeamMember (mapping an agent to a team with a specific role, resolving a many-to-many relationship).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "team_members")
@IdClass(TeamMember.TeamMemberId.class)
public class TeamMember {

    @Id
    @Column(name = "team_id")
    private String teamId;

    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Column(length = 50)
    private String role;

    /**
     * REQ-HR-1 — unified HumanReview attachable at the team-member level so an
     * operator can require approval when a specific member is dispatched in a
     * team flow (e.g. only require confirmation when the "high-risk" member is
     * picked by Router/Swarm/Planner). PR-1 is data-shape only; dispatcher
     * integration lands when the team orchestrators are wired to HumanReview
     * (after REQ-HR-3..5).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "human_review", columnDefinition = "jsonb")
    private HumanReview humanReview;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;

    public TeamMember() {
    }

    public TeamMember(String teamId, String agentId, String role) {
        this.teamId = teamId;
        this.agentId = agentId;
        this.role = role;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public HumanReview getHumanReview() {
        return humanReview;
    }

    public void setHumanReview(HumanReview humanReview) {
        this.humanReview = humanReview;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public static class TeamMemberId implements Serializable {
        private String teamId;
        private String agentId;

        public TeamMemberId() {
        }

        public TeamMemberId(String teamId, String agentId) {
            this.teamId = teamId;
            this.agentId = agentId;
        }

        public String getTeamId() {
            return teamId;
        }

        public void setTeamId(String teamId) {
            this.teamId = teamId;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TeamMemberId that = (TeamMemberId) o;
            return Objects.equals(teamId, that.teamId) && Objects.equals(agentId, that.agentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(teamId, agentId);
        }
    }
}
