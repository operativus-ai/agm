package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.WorkflowNodeLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Domain Responsibility: REQ-DR-5 persistence boundary for {@link WorkflowNodeLayout}.
 *     Layout rows are workflow-scoped (org isolation via the owning workflow row — the
 *     table has no direct org_id column).
 * State: Stateless.
 */
public interface WorkflowNodeLayoutRepository extends JpaRepository<WorkflowNodeLayout, String> {

    /** All saved node positions for a workflow. The editor loads these once and renders
     *  them in place of ELK auto-layout when non-empty. */
    List<WorkflowNodeLayout> findByWorkflowId(String workflowId);

    /** Replace-semantics helper: the save path clears the workflow's prior layout before
     *  inserting the new full set (the editor always sends the complete position map). */
    @Modifying
    @Transactional
    void deleteByWorkflowId(String workflowId);
}
