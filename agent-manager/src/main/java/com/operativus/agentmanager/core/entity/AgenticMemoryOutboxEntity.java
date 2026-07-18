package com.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain Responsibility: Represents a resilient Outbox queue item to securely separate LLM Network I/O from database transactions.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "agentic_memory_outbox")
@EntityListeners(AuditingEntityListener.class)
public class AgenticMemoryOutboxEntity {

    public enum OutboxStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    @Id
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "memory_id", nullable = false)
    private UUID memoryId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OutboxStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgenticMemoryOutboxEntity() {
    }

    public UUID getOutboxId() {
        return outboxId;
    }

    public void setOutboxId(UUID outboxId) {
        this.outboxId = outboxId;
    }

    public UUID getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(UUID memoryId) {
        this.memoryId = memoryId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
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
        AgenticMemoryOutboxEntity that = (AgenticMemoryOutboxEntity) o;
        return Objects.equals(outboxId, that.outboxId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outboxId);
    }
}
