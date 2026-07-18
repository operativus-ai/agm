package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for EvaluationCase (representing a single test scenario within an evaluation suite).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "evaluation_cases")
public class EvaluationCase {

    @Id
    private String id;

    @Column(name = "suite_id", nullable = false)
    private String suiteId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String input;

    @Column(name = "expected_output", columnDefinition = "TEXT")
    private String expectedOutput;

    @Column(name = "system_prompt_override", columnDefinition = "TEXT")
    private String systemPromptOverride;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public EvaluationCase() {}

    public EvaluationCase(String id, String suiteId, String name, String input, String expectedOutput, String systemPromptOverride) {
        this.id = id;
        this.suiteId = suiteId;
        this.name = name;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.systemPromptOverride = systemPromptOverride;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSuiteId() { return suiteId; }
    public void setSuiteId(String suiteId) { this.suiteId = suiteId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
    public String getSystemPromptOverride() { return systemPromptOverride; }
    public void setSystemPromptOverride(String systemPromptOverride) { this.systemPromptOverride = systemPromptOverride; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
