package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;

/**
 * Domain Responsibility: Represents a single unit of structured long-term agentic memory.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "agentic_memories")
@EntityListeners(AuditingEntityListener.class)
public class AgenticMemoryEntity {

    public enum MemoryTier {
        USER_PROFILE,
        USER_MEMORY,
        SESSION_CONTEXT,
        ENTITY_MEMORY,
        LEARNED_KNOWLEDGE
    }

    @Id
    @Column(name = "memory_id")
    private UUID memoryId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String memory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> topics;

    @Column(columnDefinition = "TEXT")
    private String input;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "agent_id", length = 255)
    private String agentId;

    @Column(name = "team_id", length = 255)
    private String teamId;

    @Column(name = "org_id", length = 255)
    private String orgId;

    @Column(name = "vector_id", length = 255)
    private String vectorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_tier", nullable = false, length = 50)
    private MemoryTier memoryTier;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgenticMemoryEntity() {
    }

    // Getters and Setters

    public UUID getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(UUID memoryId) {
        this.memoryId = memoryId;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getVectorId() {
        return vectorId;
    }

    public void setVectorId(String vectorId) {
        this.vectorId = vectorId;
    }

    public MemoryTier getMemoryTier() {
        return memoryTier;
    }

    public void setMemoryTier(MemoryTier memoryTier) {
        this.memoryTier = memoryTier;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgenticMemoryEntity that = (AgenticMemoryEntity) o;
        return Objects.equals(memoryId, that.memoryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(memoryId);
    }
}
