package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Domain Responsibility: Insert-only forensic row for a single orchestration decision —
 *     Router's picked target, Swarm's subtask fan-out, Planner's step list, Coordinator's
 *     member bundle. Written by {@code OrchestrationDecisionListener} off the
 *     {@code ORCHESTRATOR_DECISION} events published to {@code AgentRunEventBus}.
 *     AGM logging §5.14.
 * State: Stateful (JPA Entity — insert-only).
 *
 * <p>Complements {@code agent_run_events}: the event table preserves full timeline
 * ordering across every run event, this table is a narrow index on routing decisions
 * alone so SQL like "which agent did Router pick for org X yesterday" does not have
 * to scan timeline rows for unrelated event types.
 */
@Entity
@Table(name = "orchestration_decisions")
public class OrchestrationDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false, length = 255)
    private String runId;

    @Column(name = "org_id", length = 255)
    private String orgId;

    @Column(name = "strategy", nullable = false, length = 50)
    private String strategy;

    @Column(name = "decision_type", nullable = false, length = 50)
    private String decisionType;

    @Column(name = "selected_agent_id", length = 255)
    private String selectedAgentId;

    @Column(name = "rationale", columnDefinition = "text")
    private String rationale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_payload", columnDefinition = "jsonb")
    private Map<String, Object> decisionPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public OrchestrationDecisionEntity() {}

    public Long getId()                               { return id; }

    public String getRunId()                          { return runId; }
    public void setRunId(String v)                    { this.runId = v; }

    public String getOrgId()                          { return orgId; }
    public void setOrgId(String v)                    { this.orgId = v; }

    public String getStrategy()                       { return strategy; }
    public void setStrategy(String v)                 { this.strategy = v; }

    public String getDecisionType()                   { return decisionType; }
    public void setDecisionType(String v)             { this.decisionType = v; }

    public String getSelectedAgentId()                { return selectedAgentId; }
    public void setSelectedAgentId(String v)          { this.selectedAgentId = v; }

    public String getRationale()                      { return rationale; }
    public void setRationale(String v)                { this.rationale = v; }

    public Map<String, Object> getDecisionPayload()   { return decisionPayload; }
    public void setDecisionPayload(Map<String, Object> v) { this.decisionPayload = v; }

    public Instant getCreatedAt()                     { return createdAt; }
    public void setCreatedAt(Instant v)               { this.createdAt = v; }
}
