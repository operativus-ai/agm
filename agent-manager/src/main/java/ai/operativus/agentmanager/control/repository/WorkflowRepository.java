package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.Workflow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for the top-level definitions of Automated Workflows.
 *   The unscoped finders inherited from {@link JpaRepository} remain for system-context callers
 *   (background-job handlers, retention sweeps); user-facing reads use the org-scoped finders
 *   below to enforce tenant isolation.
 * State: Stateless
 */
@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    Page<Workflow> findAllByOrgId(String orgId, Pageable pageable);

    Optional<Workflow> findByIdAndOrgId(String id, String orgId);

    boolean existsByIdAndOrgId(String id, String orgId);
}
