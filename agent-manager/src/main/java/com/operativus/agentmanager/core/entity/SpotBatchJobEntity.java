package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.time.LocalDateTime;

@Entity
@Table(name = "spot_batch_jobs")
public class SpotBatchJobEntity {
    
    @Id
    @Column(name = "id")
    private String id;
    
    @Column(name = "job")
    private String job;
    
    @Column(name = "status")
    private String status;
    
    @Column(name = "progress")
    private Integer progress;
    
    @Column(name = "cost")
    private Double cost;
    
    @Column(name = "compute")
    private String compute;

    /**
     * Tenant identifier. Stamped at write time from {@code AgentContextHolder.getOrgId()}
     * (or {@code DEFAULT_SYSTEM_ORG} for system callers); never accepted from a request
     * body. Read on every {@code GET /api/v1/schedules/batches} call to enforce tenant
     * scoping (see {@code ScheduleService.getSpotBatches} → {@code findAllByOrgId}).
     */
    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SpotBatchJobEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getJob() { return job; }
    public void setJob(String job) { this.job = job; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    
    public Double getCost() { return cost; }
    public void setCost(Double cost) { this.cost = cost; }
    
    public String getCompute() { return compute; }
    public void setCompute(String compute) { this.compute = compute; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
