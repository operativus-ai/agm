package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for conversational Agent Sessions.
 * State: Stateless
 */
@Repository
public interface SessionRepository extends JpaRepository<AgentSession, String> {

    /**
     * @summary Retrieves a paginated list of sessions for a specific user, filtered by a minimum updated-at timestamp.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    java.util.List<AgentSession> findByUserId(String userId);

    Page<AgentSession> findByUserIdAndUpdatedAtAfter(String userId, LocalDateTime updatedAt, Pageable pageable);

    /**
     * @summary Retrieves a paginated list of sessions for a specific agent, filtered by a minimum updated-at timestamp.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    Page<AgentSession> findByAgentIdAndUpdatedAtAfter(String agentId, LocalDateTime updatedAt, Pageable pageable);

    /**
     * @summary Retrieves a paginated list of all globally visible sessions, filtered by a minimum updated-at timestamp.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    Page<AgentSession> findByUpdatedAtAfter(LocalDateTime updatedAt, Pageable pageable);

    /**
     * @summary Retrieves a paginated list of sessions within an org, filtered by a minimum updated-at timestamp.
     * @logic Derived query; org_id scope prevents cross-tenant session listing.
     */
    Page<AgentSession> findByOrgIdAndUpdatedAtAfter(String orgId, LocalDateTime updatedAt, Pageable pageable);

    /**
     * @summary Retrieves a paginated list of sessions for a specific user within an org, filtered by a minimum updated-at timestamp.
     * @logic Derived query; org_id scope prevents cross-tenant user-session listing.
     */
    Page<AgentSession> findByUserIdAndOrgIdAndUpdatedAtAfter(String userId, String orgId, LocalDateTime updatedAt, Pageable pageable);

    /**
     * @summary Retrieves a paginated list of sessions for a specific agent within an org, filtered by a minimum updated-at timestamp.
     * @logic Derived query; org_id scope prevents cross-tenant agent-session listing.
     */
    Page<AgentSession> findByAgentIdAndOrgIdAndUpdatedAtAfter(String agentId, String orgId, LocalDateTime updatedAt, Pageable pageable);

    /**
     * @summary Returns true if a session with the given session_id and org_id exists.
     * @logic Derived query used for ownership verification before mutation.
     */
    boolean existsBySessionIdAndOrgId(String sessionId, String orgId);

    /**
     * @summary Per-day session-analytics rollup for the Sessions Analytics tab
     *     (observability plan T039a). Groups sessions started since {@code since} by
     *     UTC day; for each day reports session count, p50/p95 of session duration in
     *     seconds, and the per-day average runs-per-session.
     *
     * <p>Session duration is {@code COALESCE(updated_at, NOW()) - created_at}; in
     *     practice {@code updated_at} is non-null because the entity sets it via
     *     {@code @PrePersist}/{@code @PreUpdate}.
     *
     * <p>The runs join is itself filtered by {@code created_at > :since AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)}
     *     so the avg only reflects in-window runs.
     *
     * <p>Returns rows shaped
     *     {@code [day: Instant, sessionCount: Long, p50DurationSeconds: Double, p95DurationSeconds: Double, avgRunsPerSession: Double]}.
     *     When {@code orgId} is null the tenant filter is bypassed, matching the
     *     permissive pattern used by other observability read paths.
     */
    @Query(value = """
        SELECT date_trunc('day', s.created_at AT TIME ZONE 'UTC')                         AS day,
               COUNT(DISTINCT s.session_id)                                               AS session_count,
               PERCENTILE_CONT(0.50) WITHIN GROUP
                 (ORDER BY EXTRACT(EPOCH FROM (COALESCE(s.updated_at, NOW()) - s.created_at)))
                                                                                          AS p50_duration_seconds,
               PERCENTILE_CONT(0.95) WITHIN GROUP
                 (ORDER BY EXTRACT(EPOCH FROM (COALESCE(s.updated_at, NOW()) - s.created_at)))
                                                                                          AS p95_duration_seconds,
               AVG(run_counts.n)                                                          AS avg_runs_per_session
          FROM agent_sessions s
          LEFT JOIN (
              SELECT session_id, COUNT(*) AS n
                FROM agent_runs
               WHERE created_at > :since
                 AND (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)
               GROUP BY session_id
          ) run_counts ON run_counts.session_id = s.session_id
         WHERE s.created_at > :since
           AND (:orgId IS NULL OR s.org_id IS NULL OR s.org_id = :orgId)
         GROUP BY day
         ORDER BY day ASC
        """, nativeQuery = true)
    java.util.List<Object[]> findSessionAnalytics(
            @Param("since") Instant since,
            @Param("orgId") String orgId);

    /**
     * @summary Atomic upsert used by {@code ensureSessionExists} on the agent-run hot path
     *     to close the TOCTOU window between a {@code findById} check and a follow-up
     *     {@code save} when N concurrent runs share a {@code sessionId}.
     * @logic Native Postgres {@code INSERT ... ON CONFLICT (session_id) DO UPDATE} —
     *     on first writer the row is inserted with the caller-supplied identity columns;
     *     on subsequent writers (or repeat turns) only {@code updated_at} is bumped and a
     *     null {@code agent_id} is backfilled via {@code COALESCE}. Bypasses the
     *     {@code @PrePersist}/{@code @PreUpdate} hooks and {@link com.operativus.agentmanager.compute.security.EncryptedSessionInterceptor}
     *     — neither is load-bearing for this minimal "ensure exists" path
     *     ({@code summary_blob} is not written here and the interceptor only encrypts
     *     when {@code requiresEncryption} is set, which is never true on this hot path).
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO agent_sessions (session_id, agent_id, user_id, org_id, created_at, updated_at)
        VALUES (:sessionId, :agentId, :userId, :orgId, NOW(), NOW())
        ON CONFLICT (session_id) DO UPDATE SET
            agent_id   = COALESCE(agent_sessions.agent_id, EXCLUDED.agent_id),
            updated_at = NOW()
        """, nativeQuery = true)
    void upsertEnsureExists(
            @Param("sessionId") String sessionId,
            @Param("agentId") String agentId,
            @Param("userId") String userId,
            @Param("orgId") String orgId);
}
