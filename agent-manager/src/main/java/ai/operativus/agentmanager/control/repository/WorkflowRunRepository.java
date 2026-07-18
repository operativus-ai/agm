package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Repository for persisting and retrieving active or historical chronological workflow runs.
 *   Unscoped finders (findById, inherited) remain for system-context callers (job handlers,
 *   internal engine); user-facing reads use the org-scoped finders below for tenant isolation.
 */
@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    Page<WorkflowRun> findByWorkflowIdOrderByCreatedAtDesc(String workflowId, Pageable pageable);

    Page<WorkflowRun> findByWorkflowIdAndOrgIdOrderByCreatedAtDesc(String workflowId, String orgId, Pageable pageable);

    Optional<WorkflowRun> findByIdAndOrgId(String id, String orgId);

    List<WorkflowRun> findByStatusInAndCreatedAtBefore(List<RunStatus> statuses, LocalDateTime cutoff);
}
