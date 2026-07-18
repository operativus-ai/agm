package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgentRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import ai.operativus.agentmanager.core.model.enums.RunStatus;

/**
 * Domain Responsibility: Manages persistence, retrieval, and aggregation operations for individual Agent Execution Runs.
 * State: Stateless
 */
@Repository
public interface RunRepository extends JpaRepository<AgentRun, String>, ai.operativus.agentmanager.core.registry.RunOperations {
    
    /**
     * @summary Retrieves a list of execution runs that took place within a specific session.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<AgentRun> findBySessionId(String sessionId);

    /**
     * @summary Retrieves a list of execution runs that were performed by a specific agent.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<AgentRun> findByAgentId(String agentId);

    /**
     * @summary Retrieves a paginated list of execution runs performed by a specific agent, ordered chronologically descending.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    Page<AgentRun> findByAgentIdOrderByCreatedAtDesc(String agentId, Pageable pageable);

    /**
     * @summary Retrieves a list of execution runs within a session, ordering them chronologically ascending by creation time.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<AgentRun> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * @summary Retrieves runs within a session scoped to an org, in ascending createdAt order.
     * @logic Derived query; org_id filter prevents cross-tenant run leakage via known session IDs.
     */
    List<AgentRun> findBySessionIdAndOrgIdOrderByCreatedAtAsc(String sessionId, String orgId);

    /**
     * @summary Paginated runs for a session, newest first. Backs the observability
     *          {@code GET /api/v1/runs?sessionId=…} filter (T006.3).
     * @logic Derived query; backed by {@code idx_agent_runs_session} (changeset 001).
     */
    Page<AgentRun> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /**
     * @summary Paginated runs for the dashboard "Recent Activity" widget (T015)
     *          and the observability {@code GET /api/v1/runs?status=…} filter (T006.3).
     * @logic Derived query; backed by {@code idx_agent_runs_status} (changeset 001).
     */
    Page<AgentRun> findByStatusOrderByCreatedAtDesc(RunStatus status, Pageable pageable);

    /**
     * @summary Paginated, unfiltered runs newest-first. Backs {@code GET /api/v1/runs}
     *          with no filter params (T006.3).
     * @logic Delegates to {@link JpaRepository#findAll(Pageable)} at call-sites with a
     *        {@code Sort.by(Sort.Direction.DESC, "createdAt")} pageable; this declaration
     *        exists so the call-site reads consistently with the other filter methods.
     */
    Page<AgentRun> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * @summary Paginated org-scoped runs newest-first. Tenant-safe variant of
     *     {@link #findAllByOrderByCreatedAtDesc} — backs {@code GET /api/v1/runs}
     *     with no filter params for a JWT-bound caller.
     * @logic Derived query.
     */
    Page<AgentRun> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

    /**
     * @summary Paginated org-scoped + agent-scoped runs newest-first. Tenant-safe
     *     variant of {@link #findByAgentIdOrderByCreatedAtDesc} that prevents a caller
     *     from listing another tenant's runs by guessing their agent id.
     */
    Page<AgentRun> findByAgentIdAndOrgIdOrderByCreatedAtDesc(String agentId, String orgId, Pageable pageable);

    /**
     * @summary Paginated org-scoped + session-scoped runs newest-first. Tenant-safe
     *     variant of {@link #findBySessionIdOrderByCreatedAtDesc}.
     */
    Page<AgentRun> findBySessionIdAndOrgIdOrderByCreatedAtDesc(String sessionId, String orgId, Pageable pageable);

    /**
     * @summary Paginated org-scoped + status-filtered runs newest-first. Tenant-safe
     *     variant of {@link #findByStatusOrderByCreatedAtDesc}.
     */
    Page<AgentRun> findByStatusAndOrgIdOrderByCreatedAtDesc(RunStatus status, String orgId, Pageable pageable);

    /**
     * @summary Aggregates the total number of runs globally that match a specific execution status.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    long countByStatus(RunStatus status);

    List<AgentRun> findByUserId(String userId);

    /**
     * @summary Tenant-scoped status filter — backs the escalation-resolve lookup which
     *     scans the (typically small) PAUSED set per org and matches in service code by
     *     parsing each row's {@code required_action} JSON.
     * @logic Derived query; backed by {@code idx_agent_runs_status} (changeset 001) and
     *     a Postgres bitmap-AND with {@code org_id}. Service-side parse of the JSON sidesteps
     *     a naive {@code required_action::jsonb ->> 'escalationId'} cast that would error on
     *     any legacy non-JSON value.
     */
    List<AgentRun> findByOrgIdAndStatus(String orgId, RunStatus status);

    /**
     * @summary Aggregates the total number of runs for a specific agent that match a specific execution status.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    long countByAgentIdAndStatus(String agentId, RunStatus status);

    /**
     * @summary Aggregates the total number of runs ever performed by a specific agent.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    long countByAgentId(String agentId);
    
    /**
     * @summary Retrieves the single most recent execution run tracked for a specific agent.
     * @logic Derived query executed by Spring Data JPA framework (translates to LIMIT 1).
     */
    AgentRun findFirstByAgentIdOrderByCreatedAtDesc(String agentId);

    /**
     * @summary Total USD spend for an org since a cutoff — the org's rolling/daily spend for FinOps
     *          budget enforcement ({@code DailySpendService}). COALESCE so an org with no runs in
     *          the window returns 0 rather than null. Org-scoped only (every {@code agent_runs} row
     *          carries {@code org_id}); team attribution is a separate concern (no {@code team_id}).
     */
    @Query(value = "SELECT COALESCE(SUM(total_cost_usd), 0) FROM agent_runs "
                   + "WHERE org_id = :orgId AND created_at >= :since",
           nativeQuery = true)
    java.math.BigDecimal sumCostUsdByOrgSince(@Param("orgId") String orgId,
                                              @Param("since") LocalDateTime since);

    /**
     * @summary Total USD spend since a cutoff across a set of agents — the spend attributable to a
     *          team's member agents for the team-health daily-spend metric ({@code DailySpendService
     *          .currentTeamDailySpendUsd}). COALESCE so a window with no runs returns 0 rather than
     *          null. The caller must pass a non-empty collection (Postgres rejects {@code IN ()}); an
     *          agent that belongs to multiple teams counts toward each — accepted for a per-team view.
     */
    @Query(value = "SELECT COALESCE(SUM(total_cost_usd), 0) FROM agent_runs "
                   + "WHERE agent_id IN (:agentIds) AND created_at >= :since",
           nativeQuery = true)
    java.math.BigDecimal sumCostUsdByAgentIdsSince(@Param("agentIds") java.util.Collection<String> agentIds,
                                                   @Param("since") LocalDateTime since);

    /**
     * @summary Aggregates daily run counts since a given date for historical trend analytics.
     * @logic Native Postgres GROUP BY DATE aggregation. Returns Object[] rows where:
     *        row[0] = DATE(created_at) as java.sql.Date, row[1] = run count as Long.
     *        Executed at the database level to prevent N+1 and JVM heap exhaustion.
     */
    @Query(value = "SELECT DATE(created_at) AS run_date, COUNT(*) AS run_count " +
                   "FROM agent_runs WHERE created_at >= :since " +
                   "AND (:orgId IS NULL OR org_id = :orgId) " +
                   "GROUP BY DATE(created_at) ORDER BY run_date ASC",
           nativeQuery = true)
    List<Object[]> findDailyRunCounts(@Param("since") LocalDateTime since, @Param("orgId") String orgId);

    /**
     * @summary Aggregates run counts grouped by agentId for cost allocation analytics.
     * @logic Native Postgres GROUP BY aggregation returning agentId + run count per agent.
     *        Results ordered descending by count to surface the highest-consuming agents first.
     *        When {@code orgId} is null the tenant filter is bypassed (admin/cross-org view).
     */
    @Query(value = "SELECT agent_id, COUNT(*) AS run_count " +
                   "FROM agent_runs WHERE created_at >= :since AND agent_id IS NOT NULL " +
                   "AND (:orgId IS NULL OR org_id = :orgId) " +
                   "GROUP BY agent_id ORDER BY run_count DESC",
           nativeQuery = true)
    List<Object[]> findRunCountByAgent(@Param("since") LocalDateTime since, @Param("orgId") String orgId);

    /**
     * @summary Aggregates run counts grouped by orgId for multi-tenant cost allocation analytics.
     * @logic Native Postgres GROUP BY aggregation returning orgId + run count per org unit.
     *        NULL orgId rows are surfaced as 'unattributed' in the service layer.
     *        When {@code orgId} is null the tenant filter is bypassed (admin/cross-org view).
     */
    @Query(value = "SELECT COALESCE(org_id, 'unattributed') AS org_id, COUNT(*) AS run_count " +
                   "FROM agent_runs WHERE created_at >= :since " +
                   "AND (:orgId IS NULL OR COALESCE(org_id, 'unattributed') = :orgId) " +
                   "GROUP BY COALESCE(org_id, 'unattributed') ORDER BY run_count DESC",
           nativeQuery = true)
    List<Object[]> findRunCountByOrg(@Param("since") LocalDateTime since, @Param("orgId") String orgId);

    /**
     * @summary Aggregates run counts grouped by the agent's configured LLM model for vendor cost slicing.
     * @logic Joins agent_runs with agents to resolve each run's target model_id.
     *        Returns model_id + run count per model, ordered descending by count.
     *        NULL model_id rows are surfaced as 'unknown' for the frontend fallback.
     *        When {@code orgId} is null the tenant filter is bypassed (admin/cross-org view).
     */
    @Query(value = "SELECT COALESCE(a.model_id, 'unknown') AS model_id, COUNT(*) AS run_count " +
                   "FROM agent_runs ar JOIN agents a ON ar.agent_id = a.id " +
                   "WHERE ar.created_at >= :since " +
                   "AND (:orgId IS NULL OR ar.org_id = :orgId) " +
                   "GROUP BY COALESCE(a.model_id, 'unknown') ORDER BY run_count DESC",
           nativeQuery = true)
    List<Object[]> findRunCountByModel(@Param("since") LocalDateTime since, @Param("orgId") String orgId);

    /**
     * @summary Counts runs whose target agent's {@code model_id} matches the given id, since
     *     the given cutoff. Single-id companion to {@link #findRunCountByModel(LocalDateTime)}.
     * @logic Joins {@code agent_runs} with {@code agents} on {@code agent_id} so the count
     *     reflects which model an agent is currently configured against — same shape the
     *     aggregated query uses for the dashboard model-cost slice.
     */
    @Query(value = "SELECT COUNT(*) FROM agent_runs ar JOIN agents a ON ar.agent_id = a.id " +
                   "WHERE a.model_id = :modelId AND ar.created_at >= :since",
           nativeQuery = true)
    long countRunsByModelId(@Param("modelId") String modelId, @Param("since") LocalDateTime since);

    /**
     * Retention helper: removes reflection trace rows whose parent run is about to be purged.
     * Must run BEFORE {@link #deleteByCreatedAtBefore} because
     * {@code fk_agent_reflections_run} is enforced on the referenced-side of delete.
     */
    @Modifying
    @Query(value = "DELETE FROM agent_reflections WHERE run_id IN " +
                   "(SELECT id FROM agent_runs WHERE created_at < :cutoff)",
           nativeQuery = true)
    int purgeReflectionsOfRunsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Retention helper: nulls out {@code parent_run_id} references on any run whose parent
     * is about to be purged. Prevents the self-referencing {@code fk_agent_runs_parent}
     * constraint from blocking the batch delete when only a subset of a run chain is old.
     */
    @Modifying
    @Query(value = "UPDATE agent_runs SET parent_run_id = NULL WHERE parent_run_id IN " +
                   "(SELECT id FROM agent_runs WHERE created_at < :cutoff)",
           nativeQuery = true)
    int nullifyParentRefsToRunsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Retention helper: deletes all {@link AgentRun} rows whose {@code createdAt} is strictly
     * before the cutoff. Callers must first invoke {@link #purgeReflectionsOfRunsOlderThan}
     * and {@link #nullifyParentRefsToRunsOlderThan} with the same cutoff to satisfy FK ordering.
     */
    @Modifying
    @Query("DELETE FROM AgentRun r WHERE r.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * @summary Retrieves the aggregated cost of a run tree rooted at {@code rootRunId}.
     * @logic Reads {@code vw_run_tree_cost} (changeset 036, logging plan §5.22).
     *        Returns an empty list if the given ID is not a root run (parent_run_id IS NOT NULL)
     *        or if no run with that ID exists. At most one row — the view is keyed by root_run_id.
     *        Use {@link #findTreeCostByAnyRunId} when the caller has any run ID in the tree
     *        rather than the root.
     *        Each row is {@code Object[]{root_run_id:String, tree_total_cost_usd:BigDecimal, run_count:Long}}.
     */
    @Query(value = "SELECT root_run_id AS root_run_id, tree_total_cost_usd, run_count " +
                   "FROM vw_run_tree_cost WHERE root_run_id = :rootRunId",
           nativeQuery = true)
    List<Object[]> findTreeCostByRootRunId(@Param("rootRunId") String rootRunId);

    /**
     * @summary Retrieves the run-tree cost for any run in the tree — walks parent_run_id to
     *          the root and returns the rollup for that root.
     * @logic Recursive CTE on {@code agent_runs} to find the root (parent_run_id IS NULL), then
     *        joins against the {@code vw_run_tree_cost} view. Returns an empty list if the
     *        given run ID does not exist. At most one row — a run has exactly one root ancestor.
     *        Each row is {@code Object[]{root_run_id:String, tree_total_cost_usd:BigDecimal, run_count:Long}}.
     */
    @Query(value = "WITH RECURSIVE up_tree AS ( " +
                   "  SELECT id, parent_run_id FROM agent_runs WHERE id = :runId " +
                   "  UNION ALL " +
                   "  SELECT ar.id, ar.parent_run_id FROM agent_runs ar " +
                   "  JOIN up_tree ut ON ar.id = ut.parent_run_id " +
                   ") " +
                   "SELECT v.root_run_id, v.tree_total_cost_usd, v.run_count " +
                   "FROM vw_run_tree_cost v " +
                   "JOIN up_tree ut ON v.root_run_id = ut.id " +
                   "WHERE ut.parent_run_id IS NULL",
           nativeQuery = true)
    List<Object[]> findTreeCostByAnyRunId(@Param("runId") String runId);

    /**
     * @summary Depth-limited recursive walk starting at {@code rootRunId} and descending
     *          into child runs via {@code parent_run_id} (observability plan Phase 1
     *          T006.2; resolves C3).
     * @logic Recursive CTE returning flat rows; the service layer assembles the tree.
     *        Depth is bounded in the CTE itself so Postgres stops fanning out when the
     *        limit is reached — walking a 10-deep subtree costs one index scan per level
     *        (FK {@code parent_run_id} is indexed by {@code idx_agent_runs_parent}).
     *        {@code maxDepth=0} returns just the root; the caller-side cap at 25
     *        (controller-enforced) is enforced here too as defense in depth.
     * @return {@code List<Object[]>} rows of
     *         {@code (id:String, parent_run_id:String, agent_id:String, depth:Integer,
     *          input_tokens:Long, output_tokens:Long, total_cost_usd:BigDecimal)}.
     *         Empty list if the root does not exist.
     */
    @Query(value = "WITH RECURSIVE down_tree AS ( " +
                   "  SELECT id, parent_run_id, agent_id, 0 AS depth, " +
                   "         input_tokens, output_tokens, total_cost_usd " +
                   "    FROM agent_runs WHERE id = :rootRunId " +
                   "  UNION ALL " +
                   "  SELECT ar.id, ar.parent_run_id, ar.agent_id, dt.depth + 1, " +
                   "         ar.input_tokens, ar.output_tokens, ar.total_cost_usd " +
                   "    FROM agent_runs ar " +
                   "    JOIN down_tree dt ON ar.parent_run_id = dt.id " +
                   "   WHERE dt.depth < :maxDepth " +
                   ") " +
                   "SELECT id, parent_run_id, agent_id, depth, " +
                   "       input_tokens, output_tokens, total_cost_usd " +
                   "  FROM down_tree " +
                   " ORDER BY depth ASC, id ASC",
           nativeQuery = true)
    List<Object[]> findRunCostTree(@Param("rootRunId") String rootRunId,
                                   @Param("maxDepth") int maxDepth);

    /**
     * @summary Per-agent / per-day safety-score heatmap aggregate (observability plan T033a).
     *     One row per (agent_id, day) pairing in the window. Each row carries the average
     *     and max risk scores plus a flagged count (score > 0.7) and the bucket total.
     *     Drives the Safety Analytics tab.
     *
     *     Returns rows shaped {@code [agentId, bucketDay, avgScore, maxScore, flagged, total]}.
     *     When {@code orgId} is null the tenant filter is bypassed.
     */
    @Query(value = """
        SELECT agent_id,
               date_trunc('day', created_at AT TIME ZONE 'UTC')   AS bucket_day,
               AVG(safety_risk_score)                             AS avg_score,
               MAX(safety_risk_score)                             AS max_score,
               COUNT(*) FILTER (WHERE safety_risk_score > 0.7)    AS flagged,
               COUNT(*)                                           AS total
          FROM agent_runs
         WHERE created_at > :since
           AND safety_risk_score IS NOT NULL
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
         GROUP BY agent_id, bucket_day
         ORDER BY agent_id ASC, bucket_day ASC
        """, nativeQuery = true)
    List<Object[]> findSafetyHeatmap(
            @Param("since") Instant since,
            @Param("orgId") String orgId);

    /**
     * @summary Top-N runs by safety_risk_score DESC in the window. Drives the Flagged
     *     Runs table on the Safety Analytics tab. Returns rows shaped
     *     {@code [runId, agentId, score, createdAt]}.
     */
    @Query(value = """
        SELECT id, agent_id, safety_risk_score, created_at
          FROM agent_runs
         WHERE created_at > :since
           AND safety_risk_score IS NOT NULL
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
         ORDER BY safety_risk_score DESC, created_at DESC
         LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findFlaggedRunsTop(
            @Param("since") Instant since,
            @Param("orgId") String orgId,
            @Param("limit") int limit);

    /**
     * Loads the run and acquires a row-level pessimistic write lock ({@code SELECT … FOR UPDATE})
     * for the duration of the caller's transaction. A second concurrent caller blocks at the
     * database level until the first transaction commits, then reloads the row — at which point
     * the T004b terminal-state idempotence guard fires if the first writer has already finalized
     * the run. This closes the cross-pod race where two pods both read version V before either
     * commits, making the {@link org.springframework.dao.OptimisticLockingFailureException} retry
     * in {@code AgentRunFinalizer} unnecessary for this path but harmless.
     */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AgentRun r WHERE r.id = :id")
    Optional<AgentRun> findByIdForUpdate(@Param("id") String id);

    /**
     * @summary C01 quarantine: atomically transitions every RUNNING row for {@code agentId} to
     *     CANCELLED in a single conditional UPDATE. Returns the cancelled run IDs so the
     *     incident-response audit row can record them.
     * @logic Native SQL with {@code RETURNING id} (Postgres-specific) — exactly one round-trip,
     *     row-level locks held only for the duration of the UPDATE, no per-row save loop.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE agent_runs
               SET status = 'CANCELLED',
                   output = :reason,
                   updated_at = NOW()
             WHERE agent_id = :agentId
               AND status = 'RUNNING'
            RETURNING id
            """, nativeQuery = true)
    List<String> cancelRunningByAgentId(
            @Param("agentId") String agentId,
            @Param("reason") String reason);

    /**
     * @summary C01 global kill switch: cancels EVERY RUNNING run across every tenant in one
     *     SQL statement. Returns one row per cancelled run as {@code [id, agent_id, org_id]}
     *     so the rollup audit row can record distinct affected agents and orgs.
     * @logic Single UPDATE … RETURNING; no per-row save loop. row-level locks released as
     *     each row commits.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE agent_runs
               SET status = 'CANCELLED',
                   output = :reason,
                   updated_at = NOW()
             WHERE status = 'RUNNING'
            RETURNING id, agent_id, org_id
            """, nativeQuery = true)
    List<Object[]> cancelAllRunning(@Param("reason") String reason);
}
