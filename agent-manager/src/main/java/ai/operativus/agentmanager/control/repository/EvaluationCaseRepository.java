package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.EvaluationCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for specific Evaluation Test Cases.
 * State: Stateless
 */
@Repository
public interface EvaluationCaseRepository extends JpaRepository<EvaluationCase, String> {

    /**
     * @summary Retrieves a list of evaluation cases belonging to a specific evaluation suite.
     * @logic Derived query executed by Spring Data JPA framework. Internal callers
     *        (test/scoring loops) that need the full set keep this overload.
     */
    List<EvaluationCase> findBySuiteId(String suiteId);

    /**
     * @summary G4 paginated overload — used by the public
     *          {@code GET /api/v1/evaluations/suites/{suiteId}/cases} surface so a
     *          suite with thousands of cases doesn't dump the entire set per call.
     */
    Page<EvaluationCase> findBySuiteId(String suiteId, Pageable pageable);
}
