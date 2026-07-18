package com.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for EvaluationResult (storing the outcome and metadata of a specific evaluation case execution).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "evaluation_results")
public class EvaluationResult {

    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "case_id", nullable = false)
    private String caseId;

    @Column(name = "actual_output", columnDefinition = "TEXT")
    private String actualOutput;

    @Column(name = "score")
    private Double score;

    @Column(name = "is_passing")
    private Boolean isPassing;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "token_usage_total")
    private Integer tokenUsageTotal;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public EvaluationResult() {}

    public EvaluationResult(String id, String runId, String caseId) {
        this.id = id;
        this.runId = runId;
        this.caseId = caseId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getActualOutput() { return actualOutput; }
    public void setActualOutput(String actualOutput) { this.actualOutput = actualOutput; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Boolean getIsPassing() { return isPassing; }
    public void setIsPassing(Boolean isPassing) { this.isPassing = isPassing; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public Integer getTokenUsageTotal() { return tokenUsageTotal; }
    public void setTokenUsageTotal(Integer tokenUsageTotal) { this.tokenUsageTotal = tokenUsageTotal; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
