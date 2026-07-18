package com.operativus.agentmanager.core.entity;

import com.operativus.agentmanager.core.event.AgentRunEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Domain Responsibility: Insert-only audit row for a single event in an agent run's timeline,
 *     persisted by {@code AgentRunEventBus}. Enables post-hoc forensics, orchestration replay,
 *     and FinOps aggregation across run boundaries.
 * State: Stateful (JPA Entity — insert-only).
 *
 * <p>Rows are never updated or deleted through this entity; the bus appends one row per event.
 * Partitioning (by {@code event_ts} range) is a planned follow-up — non-partitioned for MVP.
 */
@Entity
@Table(name = "agent_run_events")
public class AgentRunEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private AgentRunEventType eventType;

    @Column(name = "run_id", nullable = false, length = 255)
    private String runId;

    @Column(name = "agent_id", length = 255)
    private String agentId;

    @Column(name = "parent_run_id", length = 255)
    private String parentRunId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "org_id", length = 255)
    private String orgId;

    @Column(name = "orchestration_depth")
    private Integer orchestrationDepth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    public AgentRunEventEntity() {}

    public Long getId()                             { return id; }

    public AgentRunEventType getEventType()         { return eventType; }
    public void setEventType(AgentRunEventType v)   { this.eventType = v; }

    public String getRunId()                        { return runId; }
    public void setRunId(String v)                  { this.runId = v; }

    public String getAgentId()                      { return agentId; }
    public void setAgentId(String v)                { this.agentId = v; }

    public String getParentRunId()                  { return parentRunId; }
    public void setParentRunId(String v)            { this.parentRunId = v; }

    public String getSessionId()                    { return sessionId; }
    public void setSessionId(String v)              { this.sessionId = v; }

    public String getOrgId()                        { return orgId; }
    public void setOrgId(String v)                  { this.orgId = v; }

    public Integer getOrchestrationDepth()          { return orchestrationDepth; }
    public void setOrchestrationDepth(Integer v)    { this.orchestrationDepth = v; }

    public Map<String, Object> getPayload()         { return payload; }
    public void setPayload(Map<String, Object> v)   { this.payload = v; }

    public Instant getEventTs()                     { return eventTs; }
    public void setEventTs(Instant v)               { this.eventTs = v; }
}
