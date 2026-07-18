package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents a directional transition constraint within a multi-agent Team DAG.
 * Defines an allowed routing edge from a source agent to a target agent within the scope of a specific team.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "team_transition_edges")
public class TransitionEdge {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "team_id", nullable = false)
    private String teamId;

    @Column(name = "source_agent_id", nullable = false)
    private String sourceAgentId;

    @Column(name = "target_agent_id", nullable = false)
    private String targetAgentId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public TransitionEdge() {
    }

    public TransitionEdge(String id, String teamId, String sourceAgentId, String targetAgentId) {
        this.id = id;
        this.teamId = teamId;
        this.sourceAgentId = sourceAgentId;
        this.targetAgentId = targetAgentId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getSourceAgentId() {
        return sourceAgentId;
    }

    public void setSourceAgentId(String sourceAgentId) {
        this.sourceAgentId = sourceAgentId;
    }

    public String getTargetAgentId() {
        return targetAgentId;
    }

    public void setTargetAgentId(String targetAgentId) {
        this.targetAgentId = targetAgentId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
