package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.config.PaginationDefaultsConfig;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.AgentRunResponse;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.registry.RunOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Read-side HTTP list surface for {@code agent_runs}
 * (observability plan Phase 1 T006.3). Powers the Runs page (T008) and the Dashboard
 * Recent Activity widget (T015) — neither of which had a backend before this
 * controller existed. T003 mirrors this shape one level down for workflow runs.
 *
 * <p>Tenant + user scoping:
 * <ul>
 *   <li>Org filter via {@link AgentContextHolder#getOrgId()} on every endpoint.
 *       Pre-fix the queries were cross-tenant (the controller used
 *       {@code findAll…}/{@code findById} without an orgId predicate); a caller
 *       could enumerate ANY tenant's runs by listing without filters or by
 *       guessing run ids.</li>
 *   <li>ROLE_USER: by-id detail endpoint also enforces row.user_id matches the
 *       caller's principal. ROLE_ADMIN bypasses the user check (admin path).
 *       Same pattern as PR #673 for SessionController by-id endpoints.</li>
 *   <li>Existence-leak protection: same 404 whether the run is in another tenant,
 *       owned by another user, or absent.</li>
 * </ul>
 *
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunsController {

    private final RunRepository runRepository;
    private final AgentOperations agentOperations;

    public RunsController(RunRepository runRepository, AgentOperations agentOperations) {
        this.runRepository = runRepository;
        this.agentOperations = agentOperations;
    }

    /**
     * @summary Paginated list of runs newest-first, scoped to the caller's org.
     *     At most one of {@code sessionId|agentId|status} may be set per request;
     *     combining filters or adding date-ranges is intentionally deferred.
     * @logic
     * - Org filter is applied unconditionally for non-admin callers (super-admin
     *   with no bound orgId can see across).
     * - Multiple filters passed together → the sessionId/agentId/status precedence
     *   is applied silently. Matches how {@code @RequestParam} precedence works
     *   across other paginated controllers in this repo.
     * - Entities are mapped to {@link AgentRunResponse} so the input/output text
     *   blobs are not serialised into the list response.
     */
    @GetMapping
    public ResponseEntity<Page<AgentRunResponse>> listRuns(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "status", required = false) RunStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PaginationDefaultsConfig.clampedPageRequest(page, size);
        String orgId = AgentContextHolder.getOrgId();
        Page<AgentRun> runs;
        if (orgId != null) {
            if (sessionId != null && !sessionId.isBlank()) {
                runs = runRepository.findBySessionIdAndOrgIdOrderByCreatedAtDesc(sessionId, orgId, pageable);
            } else if (agentId != null && !agentId.isBlank()) {
                runs = runRepository.findByAgentIdAndOrgIdOrderByCreatedAtDesc(agentId, orgId, pageable);
            } else if (status != null) {
                runs = runRepository.findByStatusAndOrgIdOrderByCreatedAtDesc(status, orgId, pageable);
            } else {
                runs = runRepository.findByOrgIdOrderByCreatedAtDesc(orgId, pageable);
            }
        } else {
            // Super-admin / unauthenticated path: original behavior (cross-tenant).
            if (sessionId != null && !sessionId.isBlank()) {
                runs = runRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
            } else if (agentId != null && !agentId.isBlank()) {
                runs = runRepository.findByAgentIdOrderByCreatedAtDesc(agentId, pageable);
            } else if (status != null) {
                runs = runRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
            } else {
                runs = runRepository.findAllByOrderByCreatedAtDesc(pageable);
            }
        }
        return ResponseEntity.ok(runs.map(RunsController::toDto));
    }

    /**
     * @summary Single-run lookup for the {@code /runs/{id}} detail page (observability
     *     plan T010). Returns the same {@link AgentRunResponse} shape as the list
     *     endpoint so the page header and Overview tab can hydrate from one source.
     * @logic
     * - Loads via {@link RunOperations#findById(String)}, then enforces tenant +
     *   (for non-admin) user ownership before returning. Existence-leak protection:
     *   404 returned whether the row is missing, in another tenant, or owned by
     *   another user within the same tenant.
     */
    @GetMapping("/{runId}")
    public ResponseEntity<AgentRunResponse> getRun(@PathVariable("runId") String runId) {
        // RunRepository inherits findById(ID) from JpaRepository AND findById(String)
        // from RunOperations — disambiguate via the RunOperations view.
        RunOperations ops = runRepository;
        return ops.findById(runId)
                .filter(this::callerMayReadRun)
                .map(RunsController::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @summary User-side run cancellation. Counterpart to the admin path at
     *     {@code POST /api/admin/agents/runs/{runId}/cancel} (which is now
     *     {@code hasRole('ADMIN')}-gated). Any authenticated user may cancel a
     *     run THEY own; admins may cancel any run within their org.
     * @logic
     * - 404 if missing / different tenant / different user (existence-leak protected,
     *   same shape as {@link #getRun}).
     * - 204 on successful cancel.
     * - 204 on already-terminal rows (silent no-op) — the user may not know the
     *   latest row state, so we don't surface a 4xx like the admin path does.
     * - Delegates to {@link AgentOperations#cancelRun(String)} so the routing
     *   through {@code RunExecutionManager.cancel → AgentRunFinalizer} is identical
     *   to the admin path (same {@code @Version}-checked write contract).
     *
     * <p>Added in PR #973 because PR #969 gated {@code AgentAdminController} with
     * {@code hasRole('ADMIN')}, which broke {@code SessionDetailsPage}'s cancel
     * button for non-admin users. The FE will be migrated to this endpoint in a
     * follow-up PR; the old admin path stays for admins canceling cross-user runs
     * within their org.
     */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancelRun(@PathVariable("runId") String runId) {
        RunOperations ops = runRepository;
        return ops.findById(runId)
                .filter(this::callerMayReadRun)
                .map(run -> {
                    if (run.getStatus() == RunStatus.COMPLETED
                            || run.getStatus() == RunStatus.FAILED
                            || run.getStatus() == RunStatus.CANCELLED) {
                        // Silent no-op — user-API contract documented in
                        // AgentAdminService.cancelRun: the user may not know the
                        // latest row state, so we don't surface a 4xx like the
                        // admin path does.
                        return ResponseEntity.noContent().<Void>build();
                    }
                    agentOperations.cancelRun(runId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @return {@code true} if the caller may read this run row given the JWT-bound
     *     org and the caller's role/user id. Mirror of the {@code permittedUserId}
     *     filter pushed into SessionService in PR #673; inlined here because the
     *     RunsController has no service layer yet.
     */
    private boolean callerMayReadRun(AgentRun run) {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId != null && run.getOrgId() != null && !orgId.equals(run.getOrgId())) {
            return false;
        }
        if (isCurrentUserAdmin()) return true;
        String userId = currentUserId();
        if (userId == null) return true;  // super-admin or unauthenticated (no principal)
        return run.getUserId() == null || userId.equals(run.getUserId());
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getId() != null ? ud.getId().toString() : null;
        }
        return null;
    }

    private static boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority())) return true;
        }
        return false;
    }

    private static AgentRunResponse toDto(AgentRun r) {
        return new AgentRunResponse(
                r.getId(),
                r.getAgentId(),
                r.getSessionId(),
                r.getUserId(),
                r.getOrgId(),
                r.getParentRunId(),
                r.getStatus(),
                r.getModel(),
                r.getInputTokens(),
                r.getOutputTokens(),
                r.getDurationMs(),
                r.getTotalCostUsd(),
                r.getErrorType(),
                r.getSafetyRiskScore(),
                r.getOrchestrationStrategy(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
