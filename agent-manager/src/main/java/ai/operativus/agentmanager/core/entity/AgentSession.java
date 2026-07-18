package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Domain Responsibility: Represents the database schema and domain model for AgentSession (managing conversational context, history, and tenancy constraints).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "agent_sessions")
@EntityListeners(ai.operativus.agentmanager.compute.security.EncryptedSessionInterceptor.class)
public class AgentSession {

    @Id
    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "title")
    private String title;

    @Column(name = "session_state", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> sessionState;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "summary_blob", columnDefinition = "TEXT")
    private String summaryBlob;

    /**
     * Transient flag set by the service layer before persistence to indicate that this session's
     * content should be encrypted at rest. Avoids database queries inside JPA lifecycle callbacks.
     */
    @Transient
    private transient boolean requiresEncryption = false;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    @JsonProperty("id")
    public String getSessionId() {
        return sessionId;
    }

    @JsonProperty("id")
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Map<String, Object> getSessionState() {
        return sessionState;
    }

    public void setSessionState(Map<String, Object> sessionState) {
        this.sessionState = sessionState;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getSummaryBlob() {
        return summaryBlob;
    }

    public void setSummaryBlob(String summaryBlob) {
        this.summaryBlob = summaryBlob;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isRequiresEncryption() {
        return requiresEncryption;
    }

    public void setRequiresEncryption(boolean requiresEncryption) {
        this.requiresEncryption = requiresEncryption;
    }
}
