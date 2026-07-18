package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.time.LocalDateTime;

@Entity
@Table(name = "sandbox_capabilities")
public class SandboxCapabilityEntity {
    
    @Id
    @Column(name = "id")
    private String id;
    
    @Column(name = "agent_id")
    private String agentId;
    
    @Column(name = "thread_id")
    private String threadId;
    
    @Column(name = "active_capabilities")
    private String activeCapabilities;
    
    @Column(name = "restricted_paths")
    private String restrictedPaths;
    
    @Column(name = "memory_isolation")
    private String memoryIsolation;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SandboxCapabilityEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    
    public String getActiveCapabilities() { return activeCapabilities; }
    public void setActiveCapabilities(String activeCapabilities) { this.activeCapabilities = activeCapabilities; }
    
    public String getRestrictedPaths() { return restrictedPaths; }
    public void setRestrictedPaths(String restrictedPaths) { this.restrictedPaths = restrictedPaths; }
    
    public String getMemoryIsolation() { return memoryIsolation; }
    public void setMemoryIsolation(String memoryIsolation) { this.memoryIsolation = memoryIsolation; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
