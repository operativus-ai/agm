package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Domain Responsibility: REQ-DR-5 persistence boundary for {@link WorkflowEdge}.
 *     Edges are workflow-scoped (org isolation happens via the workflow's owning
 *     row — the workflow_edges table has no direct org_id column).
 * State: Stateless.
 */
public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdge, String> {

    /** All edges for a workflow. The DAG validator (and PR-2 dispatcher) reads this
     *  once per run-load and walks the graph from there. */
    List<WorkflowEdge> findByWorkflowIdOrderByFromStepIdAsc(String workflowId);

    /** Outbound edges from a given step. Hot path during dispatch — kept as a
     *  separate query so the dispatcher doesn't have to scan the full workflow's
     *  edges for every transition. */
    @Query("SELECT e FROM WorkflowEdge e WHERE e.fromStepId = :fromStepId ORDER BY e.condition ASC NULLS FIRST")
    List<WorkflowEdge> findByFromStepIdOrderByConditionAscNullsFirst(@Param("fromStepId") String fromStepId);

    /** Inbound edges into a given step. Used by the validator to detect unreachable
     *  steps (no inbound edges + not the start step). */
    List<WorkflowEdge> findByToStepId(String toStepId);

    long countByWorkflowId(String workflowId);
}
