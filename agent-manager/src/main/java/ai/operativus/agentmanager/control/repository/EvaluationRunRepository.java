package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.EvaluationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Execution Runs of Evaluation Suites.
 * State: Stateless
 */
@Repository
public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, String> {
    
    /**
     * @summary Retrieves a list of evaluation runs associated with a specific evaluation suite.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<EvaluationRun> findBySuiteId(String suiteId);

    /**
     * @summary Retrieves a list of evaluation runs targeted against a specific agent.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<EvaluationRun> findByAgentId(String agentId);

    /**
     * @summary Retrieves a list of evaluation runs for a specific suite, ordered by their start time in descending order.
     * @logic Derived query executed by Spring Data JPA framework. Internal callers
     *        that need the full set keep this overload.
     */
    List<EvaluationRun> findBySuiteIdOrderByStartedAtDesc(String suiteId);

    /**
     * @summary G4 paginated overload — used by the public
     *          {@code GET /api/v1/evaluations/suites/{suiteId}/runs} surface so a
     *          high-frequency-execution suite doesn't dump tens of thousands of
     *          historical runs per call. DESC ordering is enforced by the derived-
     *          method name; the {@code Pageable}'s sort field is ignored.
     */
    Page<EvaluationRun> findBySuiteIdOrderByStartedAtDesc(String suiteId, Pageable pageable);

    /**
     * @summary G2 tenant-scoped fetch — all runs whose parent suite belongs to {@code orgId}.
     * @logic Subquery against {@code EvaluationSuite.orgId} so the caller-org boundary is
     *        enforced inside the DB rather than in service code. Used by
     *        {@code EvaluationController.getEvaluationMetrics} to keep cross-tenant runs
     *        out of the aggregate.
     */
    @Query("SELECT r FROM EvaluationRun r WHERE r.suiteId IN (SELECT s.id FROM EvaluationSuite s WHERE s.orgId = :orgId)")
    List<EvaluationRun> findAllInOrg(@Param("orgId") String orgId);
}
