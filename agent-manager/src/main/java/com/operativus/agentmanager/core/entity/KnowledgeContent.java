package com.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.operativus.agentmanager.core.model.enums.RunStatus;

/**
 * Domain Responsibility: Represents the database schema and domain model for KnowledgeContent (storing metadata and status of individual documents/URLs ingested for RAG).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "knowledge_contents")
public class KnowledgeContent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;
    
    @Column(name = "content_type")
    private String contentType;

    private String uri;

    @Column(name = "content_hash")
    private String contentHash;

    private Integer size;

    @Enumerated(EnumType.STRING)
    private RunStatus status; // PROCESSING, COMPLETED, FAILED

    @Column(name = "status_message")
    private String statusMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "vector_ids")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<UUID> vectorIds;

    @Column(name = "knowledge_base_id")
    private UUID knowledgeBaseId;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "access_count")
    private int accessCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Getters and Setters omitted for brevity but required in real code.
    // For prototype, using public fields or assume Lombok (not included in pom?)
    // I'll add basic Getters/Setters to be safe.
    
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public Integer getSize() { return size; }
    public void setSize(Integer size) { this.size = size; }
    
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public String getStatusMessage() { return statusMessage; }

    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContentType() { return contentType; }

    public void setUri(String uri) { this.uri = uri; }
    public String getUri() { return uri; }

    public void setVectorIds(List<UUID> vectorIds) { this.vectorIds = vectorIds; }
    public List<UUID> getVectorIds() { return vectorIds; }

    public void setKnowledgeBaseId(UUID knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
