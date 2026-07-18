package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.OrchestrationDecisionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Append-only persistence for orchestration routing decisions.
 *     Backs the {@code orchestration_decisions} forensic table, written by
 *     {@code OrchestrationDecisionListener}. AGM logging §5.14.
 * State: Stateless (Repository Interface).
 *
 * <p>Rows are never updated or deleted through this repository; the listener appends
 * one row per {@code ORCHESTRATOR_DECISION} event. Query methods support routing
 * forensics and per-strategy reporting.
 */
@Repository
public interface OrchestrationDecisionRepository extends JpaRepository<OrchestrationDecisionEntity, Long> {

    /**
     * @summary Returns all decisions for a run in insertion order, used to reconstruct
     *     the orchestration timeline when a run spans multiple dispatch events
     *     (e.g. nested Coordinator → Router → Swarm).
     */
    List<OrchestrationDecisionEntity> findByRunIdOrderByCreatedAtAsc(String runId);

    /**
     * @summary Returns recent decisions for an org within a time window, newest first.
     *     Used by tenant-scoped routing audits and decision-rate dashboards.
     */
    List<OrchestrationDecisionEntity> findByOrgIdAndCreatedAtAfterOrderByCreatedAtDesc(String orgId, Instant since);

    /**
     * @summary Returns recent decisions for a given strategy ("ROUTER", "SWARM", …),
     *     newest first. Supports strategy-level routing-quality comparisons.
     */
    List<OrchestrationDecisionEntity> findByStrategyAndCreatedAtAfterOrderByCreatedAtDesc(String strategy, Instant since);

    /**
     * @summary Strategy-distribution aggregate (count of decisions per strategy) for an org
     *     since a given timestamp. Drives the doughnut/bar at the top of the
     *     Orchestration Analytics tab (observability plan T031a).
     *     Returns rows shaped {@code [strategy: String, count: Long]}.
     *     When {@code orgId} is null the tenant filter is bypassed (matches the
     *     permissive pattern used by other observability read paths).
     */
    @Query(value = """
        SELECT strategy, COUNT(*) AS n
          FROM orchestration_decisions
         WHERE created_at > :since
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
         GROUP BY strategy
         ORDER BY n DESC
        """, nativeQuery = true)
    List<Object[]> countDecisionsByStrategy(
            @Param("since") Instant since,
            @Param("orgId") String orgId);

    /**
     * @summary Strategy distribution bucketed over time. Drives the stacked time-series
     *     chart on the Orchestration Analytics tab.
     *     Returns rows shaped {@code [bucket: Instant, strategy: String, count: Long]}.
     *     {@code granularity} is a Postgres {@code date_trunc} unit
     *     (e.g. {@code "hour"}, {@code "day"}, {@code "week"}).
     */
    @Query(value = """
        SELECT date_trunc(:granularity, created_at AT TIME ZONE 'UTC') AS bucket,
               strategy,
               COUNT(*) AS n
          FROM orchestration_decisions
         WHERE created_at > :since
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
         GROUP BY bucket, strategy
         ORDER BY bucket ASC
        """, nativeQuery = true)
    List<Object[]> countDecisionsByStrategyOverTime(
            @Param("since") Instant since,
            @Param("orgId") String orgId,
            @Param("granularity") String granularity);

    /**
     * @summary Delegation-topology aggregate: edge counts per (caller-agent, callee-agent, strategy)
     *     since {@code since} (observability plan T037a). Drives the Delegation Topology heatmap
     *     matrix on the Analytics tab.
     *     Returns rows shaped
     *     {@code [fromAgent: String, toAgent: String, strategy: String, count: Long]}.
     *     {@code fromAgent} is the run-owning agent (i.e. the caller); rows where the caller
     *     run has no {@code agent_id} fall back to the literal {@code "(root)"} so they still
     *     surface as a row in the matrix.
     *     When {@code orgId} is null the tenant filter is bypassed, matching the permissive
     *     pattern used by other observability read paths.
     */
    @Query(value = """
        SELECT COALESCE(caller.agent_id, '(root)') AS from_agent,
               d.selected_agent_id                 AS to_agent,
               d.strategy                          AS strategy,
               COUNT(*)                            AS call_count
          FROM orchestration_decisions d
     LEFT JOIN agent_runs caller ON caller.id = d.run_id
         WHERE d.created_at > :since
           AND d.selected_agent_id IS NOT NULL
           AND (:orgId IS NULL OR d.org_id IS NULL OR d.org_id = :orgId)
         GROUP BY caller.agent_id, d.selected_agent_id, d.strategy
         ORDER BY call_count DESC
        """, nativeQuery = true)
    List<Object[]> findDelegationTopology(
            @Param("since") Instant since,
            @Param("orgId") String orgId);

    /**
     * @summary Paginated drill-down of decisions for a single strategy, newest first
     *     (observability plan T031b). Drives the per-strategy decision table on the
     *     Orchestration Analytics tab.
     *     Tenant-permissive: when {@code orgId} is null the tenant filter is bypassed,
     *     matching other observability read paths.
     */
    @Query(value = """
        SELECT *
          FROM orchestration_decisions
         WHERE strategy = :strategy
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
         ORDER BY created_at DESC
        """,
        countQuery = """
        SELECT COUNT(*)
          FROM orchestration_decisions
         WHERE strategy = :strategy
           AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
        """,
        nativeQuery = true)
    Page<OrchestrationDecisionEntity> findRecentByStrategy(
            @Param("strategy") String strategy,
            @Param("orgId") String orgId,
            Pageable pageable);
}
