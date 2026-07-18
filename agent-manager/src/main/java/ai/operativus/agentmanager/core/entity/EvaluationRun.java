package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for EvaluationRun (tracking the state and aggregated results of executing an entire evaluation suite).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "evaluation_runs")
public class EvaluationRun {

    @Id
    private String id;

    @Column(name = "suite_id", nullable = false)
    private String suiteId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "total_cases")
    private Integer totalCases;

    @Column(name = "passed_cases")
    private Integer passedCases;

    @Column(name = "failed_cases")
    private Integer failedCases;

    @Column(name = "average_score")
    private Double averageScore;

    @Column(name = "average_latency_ms")
    private Long averageLatencyMs;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public EvaluationRun() {}

    public EvaluationRun(String id, String suiteId, String agentId, RunStatus status) {
        this.id = id;
        this.suiteId = suiteId;
        this.agentId = agentId;
        this.status = status;
        this.totalCases = 0;
        this.passedCases = 0;
        this.failedCases = 0;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSuiteId() { return suiteId; }
    public void setSuiteId(String suiteId) { this.suiteId = suiteId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public Integer getTotalCases() { return totalCases; }
    public void setTotalCases(Integer totalCases) { this.totalCases = totalCases; }
    public Integer getPassedCases() { return passedCases; }
    public void setPassedCases(Integer passedCases) { this.passedCases = passedCases; }
    public Integer getFailedCases() { return failedCases; }
    public void setFailedCases(Integer failedCases) { this.failedCases = failedCases; }
    public Double getAverageScore() { return averageScore; }
    public void setAverageScore(Double averageScore) { this.averageScore = averageScore; }
    public Long getAverageLatencyMs() { return averageLatencyMs; }
    public void setAverageLatencyMs(Long averageLatencyMs) { this.averageLatencyMs = averageLatencyMs; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
