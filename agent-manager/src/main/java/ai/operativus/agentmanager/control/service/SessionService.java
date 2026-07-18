package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.entity.AgentSession;
import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.control.repository.SessionRepository;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Manages the lifecycle and retrieval of Agent conversational sessions,
 *   scoped to the caller's org so no session is accessible cross-tenant.
 * State: Stateless
 */
@Service
@Transactional
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final RunRepository runRepository;

    public SessionService(SessionRepository sessionRepository, RunRepository runRepository) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
    }

    private static final long SESSION_TTL_HOURS = 24;

    /**
     * @summary Retrieves a paginated list of active AgentSessions for the caller's org,
     *   filtered by user or agent and the 24-hour inactivity TTL.
     * @logic Resolves caller orgId first; returns an empty page when no org context is
     *   available rather than falling back to a global cross-tenant query.
     */
    public Page<AgentSession> listSessions(String userId, String agentId, Pageable pageable) {
        log.debug("Listing sessions. userId={}, agentId={}, pageable={}", userId, agentId, pageable);
        String orgId = resolveCallerOrgId();
        if (orgId == null) {
            log.warn("listSessions called without resolvable orgId — returning empty page");
            return Page.empty(pageable);
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(SESSION_TTL_HOURS);
        if (userId != null) {
            return sessionRepository.findByUserIdAndOrgIdAndUpdatedAtAfter(userId, orgId, cutoff, pageable);
        } else if (agentId != null) {
            return sessionRepository.findByAgentIdAndOrgIdAndUpdatedAtAfter(agentId, orgId, cutoff, pageable);
        } else {
            return sessionRepository.findByOrgIdAndUpdatedAtAfter(orgId, cutoff, pageable);
        }
    }

    /**
     * @summary Retrieves all execution runs associated with a specific session,
     *   scoped to the caller's org and (when set) to the caller's user id.
     * @logic Uses org-scoped query so a caller cannot enumerate runs in a session
     *   belonging to another tenant by guessing a session ID. When
     *   {@code permittedUserId} is non-null, also verifies the session itself belongs
     *   to that user — closes the within-org IDOR where a regular tenant user could
     *   read another user's runs by guessing their session id. Pass {@code null} to
     *   bypass the user check (admin path).
     */
    public List<AgentRun> listRunsForSession(String sessionId, String permittedUserId) {
        log.debug("Listing runs for session: {} permittedUserId={}", sessionId, permittedUserId);
        String orgId = resolveCallerOrgId();
        if (orgId == null) {
            log.warn("listRunsForSession called without resolvable orgId — returning empty list");
            return List.of();
        }
        if (permittedUserId != null) {
            // Existence-leak protection: same empty response whether the session is
            // missing, in another org, or owned by another user in the same org.
            Optional<AgentSession> owned = sessionRepository.findById(sessionId)
                    .filter(s -> orgId.equals(s.getOrgId()))
                    .filter(s -> permittedUserId.equals(s.getUserId()));
            if (owned.isEmpty()) {
                log.debug("listRunsForSession refused: session {} not owned by user {} in org {}",
                        sessionId, permittedUserId, orgId);
                return List.of();
            }
        }
        return runRepository.findBySessionIdAndOrgIdOrderByCreatedAtAsc(sessionId, orgId);
    }

    /**
     * @summary Retrieves a specific AgentSession by ID, validating org and (when set)
     *   user ownership plus the inactivity TTL.
     * @logic Returns empty when the session belongs to a different org (cross-tenant
     *   protection), or — if {@code permittedUserId} is non-null — when the session
     *   belongs to a different user within the same org (intra-tenant cross-user
     *   IDOR protection). Pass {@code null} to bypass the user check (admin path).
     *   Existence-leak protection: 404 regardless of whether the session exists.
     */
    public Optional<AgentSession> getSession(String sessionId, String permittedUserId) {
        log.debug("Getting session details for ID: {} permittedUserId={}", sessionId, permittedUserId);
        String orgId = resolveCallerOrgId();
        if (orgId == null) {
            log.warn("getSession called without resolvable orgId — returning empty");
            return Optional.empty();
        }
        return sessionRepository.findById(sessionId)
                .filter(s -> orgId.equals(s.getOrgId()))
                .filter(s -> permittedUserId == null || permittedUserId.equals(s.getUserId()))
                .map(session -> {
                    if (session.getUpdatedAt().plusHours(SESSION_TTL_HOURS).isBefore(LocalDateTime.now())) {
                        throw new BusinessValidationException("Session has expired due to inactivity.");
                    }
                    return session;
                });
    }

    /**
     * @summary Deletes an AgentSession record, enforcing org ownership and (when set)
     *   user ownership.
     * @logic Silently no-ops when the session is not owned by the caller's org or
     *   (when {@code permittedUserId} is non-null) by the caller's user — existence-
     *   leak protection: the controller returns 204 in all cases. Pass {@code null}
     *   to bypass the user check (admin path).
     */
    public void deleteSession(String sessionId, String permittedUserId) {
        log.info("Deleting session ID: {} permittedUserId={}", sessionId, permittedUserId);
        String orgId = resolveCallerOrgId();
        if (orgId == null) {
            log.warn("deleteSession refused: orgId unresolvable, sessionId={}", sessionId);
            return;
        }
        if (permittedUserId != null) {
            Optional<AgentSession> owned = sessionRepository.findById(sessionId)
                    .filter(s -> orgId.equals(s.getOrgId()))
                    .filter(s -> permittedUserId.equals(s.getUserId()));
            if (owned.isEmpty()) {
                log.warn("deleteSession refused: session {} not owned by user {} in org {}",
                        sessionId, permittedUserId, orgId);
                return;
            }
        } else if (!sessionRepository.existsBySessionIdAndOrgId(sessionId, orgId)) {
            log.warn("deleteSession refused: session {} not in caller org {}", sessionId, orgId);
            return;
        }
        sessionRepository.deleteById(sessionId);
        log.debug("Successfully deleted session with ID: {}", sessionId);
    }

    /**
     * Resolves the caller's orgId from {@link AgentContextHolder} (agent-run ScopedValue path),
     * falling back to {@code SecurityContextHolder → UserDetailsImpl} (HTTP path). Returns
     * {@code null} when neither context is bound — callers must treat that as a hard refusal
     * (return empty / no-op), never as "match all orgs".
     */
    private String resolveCallerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId != null && !orgId.isBlank()) return orgId;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getOrgId();
        }
        return null;
    }
}
