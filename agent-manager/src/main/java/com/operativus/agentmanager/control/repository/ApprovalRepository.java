package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.Approval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Human-in-the-Loop Approval records.
 * State: Stateless
 */
@Repository
public interface ApprovalRepository extends JpaRepository<Approval, String> {
    
    /**
     * @summary Retrieves a list of approvals associated with a specific session ID.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<Approval> findBySessionId(String sessionId);

    /**
     * @summary Retrieves a list of approvals associated with a specific run ID.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<Approval> findByRunId(String runId);

    /**
     * @summary Retrieves the most recent approval for a run, or empty if none exists.
     * @logic Used by the stuck-PAUSED triage classifier (RunExecutionManager) to
     *        determine WHY a run has been paused — looks at the latest approval's status.
     */
    java.util.Optional<Approval> findFirstByRunIdOrderByCreatedAtDesc(String runId);

    /**
     * @summary Retrieves a list of approvals filtered by their required status.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<Approval> findByStatus(RunStatus status);

    /**
     * @summary Retrieves a paginated set of approvals filtered by status.
     * @logic Derived query executed by Spring Data JPA framework with Pageable support.
     */
    Page<Approval> findByStatus(RunStatus status, Pageable pageable);

    /**
     * @summary Retrieves approvals scoped to a single tenant by status + org.
     * @logic Explicit org filter — the canonical mechanism for tenant isolation on this
     *        surface. Invoked when a caller supplies the X-Org-Id header so the
     *        pending-approvals listing cannot leak rows across tenants.
     */
    Page<Approval> findByStatusAndOrgId(RunStatus status, String orgId, Pageable pageable);

    List<Approval> findByStatusAndOrgId(RunStatus status, String orgId);

    List<Approval> findByRequestedBy(String requestedBy);
}

