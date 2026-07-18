package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.entity.AgentSession;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.control.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springdoc.core.annotations.ParameterObject;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Exposes REST APIs for listing, retrieving, and deleting Agent conversational Sessions.
 * State: Stateless
 * Dependencies: SessionService
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * @summary Retrieves a paginated list of sessions, optionally filtered by User or Agent.
     * @logic
     * - Tenant scope is enforced at the {@link SessionService} layer (every method reads
     *   the caller's orgId from {@code AgentContextHolder}).
     * - Cross-USER enumeration was previously open: a regular tenant user could list any
     *   other user's sessions within their org by setting {@code ?userId=other-user}.
     *   This fix gates the {@code userId} param by role — regular users have their
     *   {@code userId} forcibly overridden to their authenticated principal's id (filter
     *   collapses to "my sessions"), while admins retain full filter access.
     * - Matches the §28 RBAC pattern: admins can see across, regular users see their own.
     */
    @GetMapping
    public Page<AgentSession> listSessions(@RequestParam(required = false) String userId,
                                           @RequestParam(required = false) String agentId,
                                           @ParameterObject Pageable pageable) {
        String effectiveUserId = isCurrentUserAdmin() ? userId : currentUserId();
        return sessionService.listSessions(effectiveUserId, agentId, pageable);
    }

    /**
     * Resolves the authenticated principal's user id (UUID-as-string) from the
     * {@link SecurityContextHolder}. Returns {@code null} when no authentication is
     * bound — service layer will then short-circuit on the orgId check.
     */
    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getId() != null ? ud.getId().toString() : null;
        }
        return null;
    }

    /**
     * @return {@code true} if the authenticated principal carries {@code ROLE_ADMIN}.
     *     Unauthenticated callers default to non-admin (the listing service-side
     *     orgId scope will reject them anyway).
     */
    private static boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority())) return true;
        }
        return false;
    }

    /**
     * @summary Fetches metadata details for a specifically targeted conversational session.
     * @logic
     * - Tenant scope: SessionService enforces orgId match.
     * - User scope: regular ROLE_USER callers may only read sessions they own; admins
     *   may read any session in their org. The principal user id is passed to the
     *   service as the {@code permittedUserId} filter (or {@code null} for admins).
     * - 404 returned uniformly whether the session is missing, in another org, or owned
     *   by another user — existence-leak protection.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<AgentSession> getSession(@PathVariable String sessionId) {
        log.debug("Fetching session details for sessionId: {}", sessionId);
        String permittedUserId = isCurrentUserAdmin() ? null : currentUserId();
        return sessionService.getSession(sessionId, permittedUserId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Session not found: {}", sessionId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * @summary Provides all discrete AI execution Runs that have occurred under a specific Session umbrella.
     * @logic
     * - Tenant + user scope identical to {@link #getSession}: ROLE_USER may only list
     *   runs of sessions they own; admins may list runs of any session in their org.
     */
    @GetMapping("/{sessionId}/runs")
    public List<ai.operativus.agentmanager.core.entity.AgentRun> getSessionRuns(@PathVariable String sessionId) {
        String permittedUserId = isCurrentUserAdmin() ? null : currentUserId();
        return sessionService.listRunsForSession(sessionId, permittedUserId);
    }

    /**
     * @summary Performs a hard delete on a specific conversational Session trace.
     * @logic
     * - Tenant + user scope identical to {@link #getSession}: ROLE_USER may only delete
     *   sessions they own; admins may delete any session in their org. 204 returned
     *   in all cases (existence-leak protection).
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        log.info("Deleting session: {}", sessionId);
        String permittedUserId = isCurrentUserAdmin() ? null : currentUserId();
        sessionService.deleteSession(sessionId, permittedUserId);
        return ResponseEntity.noContent().build();
    }
}
