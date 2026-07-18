package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.EvaluationSuite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Evaluation Suites (collections of test cases).
 * State: Stateless
 */
@Repository
public interface EvaluationSuiteRepository extends JpaRepository<EvaluationSuite, String> {

    /** G2 tenant-scoped list (unbounded — kept for internal callers that need the full set). */
    List<EvaluationSuite> findAllByOrgId(String orgId);

    /**
     * G3 paginated overload — used by the public {@code GET /api/v1/evaluations/suites}
     * surface. Caps the response so a long-lived tenant with thousands of suites doesn't
     * dump the entire set per call.
     */
    Page<EvaluationSuite> findAllByOrgId(String orgId, Pageable pageable);

    /** G2 tenant-scoped lookup. */
    Optional<EvaluationSuite> findByIdAndOrgId(String id, String orgId);

    /** G2 ownership check used by child-path guards (cases, runs, results, run-trigger). */
    boolean existsByIdAndOrgId(String id, String orgId);
}
