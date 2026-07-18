package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.WorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for the individual Sequential Steps contained within an Automated Workflow.
 * State: Stateless
 */
@Repository
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, String> {
    
    /**
     * @summary Retrieves the sequential list of execution steps belonging to a specific workflow definition, ordered by step sequence.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<WorkflowStep> findByWorkflowIdOrderByStepOrderAsc(String workflowId);

    /**
     * @summary Unconditionally removes all step definitions associated with a specific workflow ID.
     * @logic Derived query mapped to a delete execution by Spring Data JPA framework.
     */
    void deleteByWorkflowId(String workflowId);

    /**
     * @summary Returns the number of step definitions attached to a workflow.
     * @logic Used by WorkflowsController.executeWorkflow to reject Run requests against
     *     0-step workflows up front — without this guard the background WORKFLOW_EXECUTION
     *     job tries to insert a workflow_runs row that violates the session_id FK because
     *     no step ever creates the session.
     */
    long countByWorkflowId(String workflowId);

    /**
     * @summary Batch step-count for a set of workflows, keyed by workflow ID.
     * @logic Single GROUP BY query backing the workflows list view's "Steps" column — avoids
     *     an N+1 of per-row {@link #countByWorkflowId} across a page. Workflows with zero steps
     *     are absent from the result (no row to group); callers default those to 0.
     */
    @Query("SELECT s.workflowId AS workflowId, COUNT(s) AS count FROM WorkflowStep s "
            + "WHERE s.workflowId IN :workflowIds GROUP BY s.workflowId")
    List<WorkflowStepCount> countByWorkflowIdIn(Collection<String> workflowIds);

    /** Projection carrier for {@link #countByWorkflowIdIn}. */
    interface WorkflowStepCount {
        String getWorkflowId();
        long getCount();
    }
}
