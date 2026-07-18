package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgentRunEventEntity;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Append-only persistence for agent run timeline events.
 *     Backs the {@code agent_run_events} audit table, written by {@code AgentRunEventBus}.
 * State: Stateless (Repository Interface).
 *
 * <p>Rows are never updated or deleted through this repository; every bus event appends
 * a single row. Query methods support post-hoc forensics and orchestration replay.
 */
@Repository
public interface AgentRunEventRepository extends JpaRepository<AgentRunEventEntity, Long> {

    /**
     * @summary Returns all events for a run ordered by emission time, used to reconstruct
     *     a single run's timeline (RUN_START → TOOL_INVOKED → … → RUN_COMPLETE).
     */
    List<AgentRunEventEntity> findByRunIdOrderByEventTsAsc(String runId);

    /**
     * @summary Returns events of a specific type for a run — e.g. only TOOL_INVOKED rows
     *     for tool-trace views, or only ORCHESTRATOR_DECISION rows for routing audits.
     */
    List<AgentRunEventEntity> findByRunIdAndEventTypeOrderByEventTsAsc(String runId, AgentRunEventType eventType);

    /**
     * @summary Returns recent events for an org within a time window, newest first.
     *     Used by tenant-scoped dashboards and the SSE replay endpoint (§5.17).
     */
    List<AgentRunEventEntity> findByOrgIdAndEventTsAfterOrderByEventTsDesc(String orgId, Instant since);

    /**
     * @summary Returns events for a run with {@code id > sinceId}, ordered by monotonically
     *     increasing {@code id}. Drives the cursor for {@code RunEventSseController} replay
     *     and live-poll streaming per Risk R-21 (cursor on {@code id}, not {@code event_ts},
     *     to avoid ties and clock-skew ambiguity).
     */
    List<AgentRunEventEntity> findByRunIdAndIdGreaterThanOrderByIdAsc(String runId, Long sinceId);

    /**
     * @summary Returns events for an agent (across all of its runs) with {@code id > sinceId},
     *     strictly scoped to {@code orgId} and ordered by monotonically increasing {@code id}.
     *     Drives the long-lived per-agent SSE stream ({@code AgentEventSseController}). The
     *     {@code orgId} predicate is in the query itself — never an agentId-only lookup — to
     *     avoid the cross-tenant IDOR class: org B must not tail org A's agent events.
     */
    List<AgentRunEventEntity> findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(
            String agentId, String orgId, Long sinceId);

    /**
     * @summary Highest event id for an agent within the caller's tenant, or {@code null} when the
     *     agent has no events visible to that org. Lets a per-agent SSE client start at the live tail
     *     (start-from-latest) instead of replaying the full, potentially large history. The org
     *     predicate mirrors {@link #findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc} exactly (a null
     *     {@code orgId} matches only null-org rows) so the resolved cursor is consistent with the rows
     *     the stream will actually emit.
     */
    @Query("""
        SELECT MAX(e.id) FROM AgentRunEventEntity e
        WHERE e.agentId = :agentId
          AND ((:orgId IS NULL AND e.orgId IS NULL) OR e.orgId = :orgId)
        """)
    Long findMaxIdByAgentIdAndOrgId(@Param("agentId") String agentId, @Param("orgId") String orgId);

    /**
     * @summary Org-wide resumable feed: events for the whole tenant (every agent) with
     *     {@code id > sinceId}, ordered by monotonic {@code id}. Drives the org-wide "all agents"
     *     SSE stream (the enterprise org-event SSE controller). Strictly org-scoped (a null {@code orgId}
     *     matches only null-org rows) so one tenant can never tail another's events.
     */
    List<AgentRunEventEntity> findByOrgIdAndIdGreaterThanOrderByIdAsc(String orgId, Long sinceId);

    /**
     * @summary Highest event id within a tenant, or {@code null} when the org has no events. Lets the
     *     org-wide SSE client start at the live tail instead of replaying the full history. The org
     *     predicate mirrors {@link #findByOrgIdAndIdGreaterThanOrderByIdAsc} exactly.
     */
    @Query("""
        SELECT MAX(e.id) FROM AgentRunEventEntity e
        WHERE (:orgId IS NULL AND e.orgId IS NULL) OR e.orgId = :orgId
        """)
    Long findMaxIdByOrgId(@Param("orgId") String orgId);

    /**
     * @summary Returns events of {@code eventType} after {@code since} for the given org,
     *     ordered ascending by emission time. Drives the cursor-based budget-exceeded feed
     *     (observability plan T030a). When {@code orgId} is null the tenant filter is bypassed
     *     to match the permissive pattern used by other observability read paths.
     */
    @Query("""
        SELECT e FROM AgentRunEventEntity e
        WHERE e.eventType = :eventType
          AND e.eventTs > :since
          AND (:orgId IS NULL OR e.orgId IS NULL OR e.orgId = :orgId)
        ORDER BY e.eventTs ASC
        """)
    List<AgentRunEventEntity> findFeedByEventTypeAndOrg(
            @Param("eventType") AgentRunEventType eventType,
            @Param("since") Instant since,
            @Param("orgId") String orgId,
            Pageable pageable);

    /**
     * @summary Tool-usage rollup. For each {@code TOOL_COMPLETED} event in window,
     *     emits one row {@code [toolName, totalCount, errorCount, avgDurationMs]}.
     *     Drives the Tools Analytics tab (T035b).
     *
     * <p><b>Note:</b> The plan's T035a calls for a denormalized {@code tool_name}
     * column on {@code agent_run_events} with a partial index. That migration is
     * deferred — this query reads {@code payload->>'toolName'} (JSONB extraction)
     * directly. Acceptable for the dashboard's polling cadence over 30-90 day
     * windows; a dedicated index can be added later when scale demands.
     */
    @Query(value = """
        SELECT payload->>'toolName' AS tool_name,
               COUNT(*)              AS total_count,
               SUM(CASE WHEN payload->>'status' = 'error' THEN 1 ELSE 0 END) AS error_count,
               AVG(CAST(payload->>'durationMs' AS BIGINT))                   AS avg_duration_ms
          FROM agent_run_events
         WHERE event_type = 'TOOL_COMPLETED'
           AND event_ts > :since
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
           AND payload->>'toolName' IS NOT NULL
         GROUP BY payload->>'toolName'
         ORDER BY total_count DESC
        """, nativeQuery = true)
    List<Object[]> aggregateToolUsage(
            @Param("since") Instant since,
            @Param("orgId") String orgId);

    /**
     * @summary Tool-usage time-bucketed series. Returns rows
     *     {@code [bucket, toolName, count]} for stacked time-series charts.
     *     {@code granularity} is a Postgres {@code date_trunc} unit
     *     ({@code "hour"|"day"|"week"|"month"}).
     */
    @Query(value = """
        SELECT date_trunc(:granularity, event_ts AT TIME ZONE 'UTC') AS bucket,
               payload->>'toolName'                                  AS tool_name,
               COUNT(*)                                              AS n
          FROM agent_run_events
         WHERE event_type = 'TOOL_COMPLETED'
           AND event_ts > :since
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
           AND payload->>'toolName' IS NOT NULL
         GROUP BY bucket, payload->>'toolName'
         ORDER BY bucket ASC
        """, nativeQuery = true)
    List<Object[]> aggregateToolUsageOverTime(
            @Param("since") Instant since,
            @Param("orgId") String orgId,
            @Param("granularity") String granularity);
}
