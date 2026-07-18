package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for Evaluation (defining test criteria and metrics for assessing agent performance).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "evaluations")
public class Evaluation {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status; // RUNNING, COMPLETED, FAILED

    private Double score;

    @Column(columnDefinition = "TEXT")
    private String input;

    @Column(columnDefinition = "TEXT")
    private String output;

    @Column(name = "expected_output", columnDefinition = "TEXT")
    private String expectedOutput;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Evaluation() {}

    public Evaluation(String id, String name, String agentId, String input, String expectedOutput) {
        this.id = id;
        this.name = name;
        this.agentId = agentId;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.status = RunStatus.CREATED;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
