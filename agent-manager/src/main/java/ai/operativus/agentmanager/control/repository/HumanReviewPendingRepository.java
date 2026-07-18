package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link HumanReviewPending}. Hot path: {@link #findExpired}
 * is the poller's query, backed by the partial index
 * {@code idx_human_review_pending_expires_open}.
 */
public interface HumanReviewPendingRepository extends JpaRepository<HumanReviewPending, String> {

    /** Per-tenant lookup for the admin list endpoint (REQ-HR-5). */
    List<HumanReviewPending> findByOrgIdOrderByCreatedAtDesc(String orgId);

    /** Per-run lookup for "what's pending on this run?" queries. */
    List<HumanReviewPending> findByRunIdOrderByCreatedAtDesc(String runId);

    /** Find a specific pending row scoped to the caller's org (existence-leak protection §79). */
    Optional<HumanReviewPending> findByIdAndOrgId(String id, String orgId);

    /**
     * Poller hot path — undecided rows whose {@code expires_at} has passed.
     * Matches the partial index for cheap scan as decided rows accumulate.
     */
    @Query("""
            SELECT p FROM HumanReviewPending p
             WHERE p.decision IS NULL
               AND p.expiresAt IS NOT NULL
               AND p.expiresAt <= :now
            """)
    List<HumanReviewPending> findExpired(@Param("now") Instant now);
}
