package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.WorkflowNodeRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Domain Responsibility: Persistence for {@link WorkflowNodeRun} — the per-node execution trace
 *     of a DAG workflow run (REQ-DR-5, DAG-3a). Reads are run-scoped; tenancy is enforced on the
 *     parent {@code workflow_runs} row, so callers must resolve the run's org before exposing
 *     these rows over REST.
 * State: Stateless.
 */
public interface WorkflowNodeRunRepository extends JpaRepository<WorkflowNodeRun, String> {

    /** All node executions for a run, oldest first — the run's node trace. */
    List<WorkflowNodeRun> findByRunIdOrderByStartedAtAsc(String runId);

    /**
     * All node executions for a run ordered by attempt ascending — the DAG-3c resume reconstruction
     * source. Folding this into a {@code Map<nodeId, row>} yields the latest attempt per node (later
     * attempts overwrite earlier ones); the caller keeps only terminal SUCCESS rows to rebuild the
     * completed-node {@code outputs} map without duplicating it into the persisted frontier.
     */
    List<WorkflowNodeRun> findByRunIdOrderByAttemptAsc(String runId);

    long countByRunId(String runId);

    /**
     * All node executions whose run id starts with the given prefix — the child (and deeper
     * descendant) traces of a parent run's nested sub-workflows, which execute under derived run
     * ids of the form {@code <parentRunId>#<nodeId>#<uuid>} (recursively). Callers pass
     * {@code parentRunId + "#"}.
     */
    List<WorkflowNodeRun> findByRunIdStartingWithOrderByStartedAtAsc(String runIdPrefix);
}
