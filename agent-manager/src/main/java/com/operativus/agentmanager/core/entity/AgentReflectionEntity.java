package com.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain Responsibility: Persists a single reflection trace node produced during agent execution.
 * Captures the reasoning lineage, self-correction heuristics, and Actor-Critic pivot decisions
 * that drive multi-step LLM orchestrations. Enables offline analysis, debugging, and RL-style
 * feedback loops on agent decision quality.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "agent_reflections")
@EntityListeners(AuditingEntityListener.class)
public class AgentReflectionEntity {

    @Id
    @Column(name = "reflection_id")
    private UUID reflectionId;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    @Column(name = "phase", length = 50, nullable = false)
    private String phase;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "correction_applied", columnDefinition = "boolean default false")
    private Boolean correctionApplied = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls_snapshot", columnDefinition = "jsonb")
    private List<String> toolCallsSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private java.util.Map<String, Object> metadata;

    @Column(name = "orchestration_depth")
    private Integer orchestrationDepth;

    @Column(name = "parent_reflection_id")
    private UUID parentReflectionId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public AgentReflectionEntity() {
    }

    // Getters and Setters

    public UUID getReflectionId() {
        return reflectionId;
    }

    public void setReflectionId(UUID reflectionId) {
        this.reflectionId = reflectionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(Integer stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Boolean getCorrectionApplied() {
        return correctionApplied;
    }

    public void setCorrectionApplied(Boolean correctionApplied) {
        this.correctionApplied = correctionApplied;
    }

    public List<String> getToolCallsSnapshot() {
        return toolCallsSnapshot;
    }

    public void setToolCallsSnapshot(List<String> toolCallsSnapshot) {
        this.toolCallsSnapshot = toolCallsSnapshot;
    }

    public java.util.Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(java.util.Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Integer getOrchestrationDepth() {
        return orchestrationDepth;
    }

    public void setOrchestrationDepth(Integer orchestrationDepth) {
        this.orchestrationDepth = orchestrationDepth;
    }

    public UUID getParentReflectionId() {
        return parentReflectionId;
    }

    public void setParentReflectionId(UUID parentReflectionId) {
        this.parentReflectionId = parentReflectionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentReflectionEntity that = (AgentReflectionEntity) o;
        return Objects.equals(reflectionId, that.reflectionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reflectionId);
    }
}
