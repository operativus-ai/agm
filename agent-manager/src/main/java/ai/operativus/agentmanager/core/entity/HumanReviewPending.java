package ai.operativus.agentmanager.core.entity;

import ai.operativus.agentmanager.core.model.HumanReview;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Domain Responsibility: REQ-HR-2 — JPA entity backing the
 *     {@code human_review_pending} table. One row per outstanding pause —
 *     created by {@code HumanReviewService.pauseFor}, settled by either
 *     {@code decide()} (operator action) or the timeout poller.
 *
 *     <p>Subject-type discriminator routes the resume-handler dispatch:
 *     {@code WORKFLOW_STEP} → REQ-HR-3 handler, {@code AGENT_TOOL_CALL} →
 *     REQ-HR-4 handler, etc. The {@code options} JSONB carries the
 *     {@link HumanReview} struct verbatim (so timeout poller can read
 *     {@code on_timeout} without re-fetching the originating row) plus any
 *     per-pause context (e.g. CONDITION's evaluated input).
 *
 *     <p>{@code expires_at NULL} = no timeout (operator must explicitly decide).
 *     Non-null is set from {@code HumanReview.timeoutSeconds} at pause time.
 *     The poller's partial index covers {@code expires_at IS NOT NULL AND
 *     decision IS NULL} so scans stay cheap.
 *
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "human_review_pending")
public class HumanReviewPending {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "subject_type", nullable = false, length = 32)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", columnDefinition = "jsonb")
    private Map<String, Object> options;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision", length = 32)
    private String decision;

    @Column(name = "decided_by")
    private String decidedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    public HumanReviewPending() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
