package ai.operativus.agentmanager.core.entity;

import ai.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Append-only audit record for each lifecycle state transition
 * of an inbound A2A task execution.
 *
 * Design gap fix: {@code A2ATaskExecutor} previously emitted lifecycle events only to
 * the SSE stream, leaving no durable record if the client disconnected or the task
 * failed silently. This entity persists every state transition to {@code a2a_task_events},
 * enabling post-hoc forensics, FinOps auditing, and cross-boundary trace reconstruction.
 *
 * Insert-only: rows are never updated or deleted. Each call to
 * {@code A2aTaskEventRepository.save()} appends a new event row.
 *
 * State: Stateful (Data Carrier / JPA Entity — insert-only)
 */
@Entity
@Table(name = "a2a_task_events")
public class A2aTaskEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Caller-assigned idempotency key for this task — matches {@code A2aTaskRequest.taskId()}. */
    @Column(name = "task_id", nullable = false, length = 255)
    private String taskId;

    /** AGM internal run ID — null for SUBMITTED events (run not yet started). */
    @Column(name = "run_id", length = 255)
    private String runId;

    @Column(name = "target_agent_id", nullable = false, length = 255)
    private String targetAgentId;

    @Column(name = "initiating_agent", length = 255)
    private String initiatingAgent;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    /** OpenTelemetry Trace ID propagated from the calling peer (Gap 2.3). */
    @Column(name = "trace_id", length = 255)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private A2aTaskStatus status;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    @Column(name = "event_ts", nullable = false)
    private LocalDateTime eventTs;

    public A2aTaskEventEntity() {}

    public Long getId()                         { return id; }

    public String getTaskId()                   { return taskId; }
    public void setTaskId(String v)             { this.taskId = v; }

    public String getRunId()                    { return runId; }
    public void setRunId(String v)              { this.runId = v; }

    public String getTargetAgentId()            { return targetAgentId; }
    public void setTargetAgentId(String v)      { this.targetAgentId = v; }

    public String getInitiatingAgent()          { return initiatingAgent; }
    public void setInitiatingAgent(String v)    { this.initiatingAgent = v; }

    public String getSessionId()                { return sessionId; }
    public void setSessionId(String v)          { this.sessionId = v; }

    public String getTraceId()                  { return traceId; }
    public void setTraceId(String v)            { this.traceId = v; }

    public A2aTaskStatus getStatus()            { return status; }
    public void setStatus(A2aTaskStatus v)      { this.status = v; }

    public String getMessage()                  { return message; }
    public void setMessage(String v)            { this.message = v; }

    public String getErrorDetail()              { return errorDetail; }
    public void setErrorDetail(String v)        { this.errorDetail = v; }

    public LocalDateTime getEventTs()           { return eventTs; }
    public void setEventTs(LocalDateTime v)     { this.eventTs = v; }
}
