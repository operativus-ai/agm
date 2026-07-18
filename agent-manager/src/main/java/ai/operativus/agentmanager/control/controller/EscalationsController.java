package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.security.CallerContext;
import ai.operativus.agentmanager.control.service.EscalationResolveService;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.model.EscalationResolveResponse;
import ai.operativus.agentmanager.core.model.ResolveDecisionRequest;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Domain Responsibility: Exposes the REST surface for resolving a Human-in-the-Loop swarm
 *     escalation. Tenant scope is resolved exclusively from the authenticated SecurityContext
 *     principal — never from request headers — and every operation is bounded to that orgId.
 *     Cross-tenant reads/writes return 404 (existence is never leaked).
 *
 *     Sister surface to {@link ApprovalsController}: that one keys on {@code approvalId} for
 *     {@link ai.operativus.agentmanager.core.model.RequiredActionType#TOOL_APPROVAL}; this
 *     one keys on {@code escalationId} for
 *     {@link ai.operativus.agentmanager.core.model.RequiredActionType#SWARM_ESCALATION_APPROVAL}.
 *     Both delegate the actual run resumption to {@code AgentOperations.continueRun}.
 *
 * State: Stateless
 * Dependencies: {@link EscalationResolveService}
 */
@RestController
@RequestMapping("/api/v1/escalations")
@PreAuthorize("isAuthenticated()")
public class EscalationsController {

    private final EscalationResolveService escalationResolveService;

    public EscalationsController(EscalationResolveService escalationResolveService) {
        this.escalationResolveService = escalationResolveService;
    }

    /**
     * @summary Resolves a paused swarm escalation as APPROVED or REJECTED. The agent run
     *     paused with this {@code escalationId} resumes asynchronously; the response returns
     *     the underlying {@code runId} so the caller can re-poll run status.
     * @logic Tenant-checks the escalation against the caller's orgId; rejects cross-tenant
     *     attempts with 404. Decision must be {@code APPROVED} or {@code REJECTED} — any
     *     other value is a 400.
     */
    @PostMapping("/{escalationId}/resolve")
    public ResponseEntity<EscalationResolveResponse> resolveEscalation(
            @PathVariable("escalationId") String escalationId,
            @RequestBody @Valid ResolveDecisionRequest payload) {

        String decisionStr = payload.decision();

        RunStatus decision;
        try {
            decision = RunStatus.fromValue(decisionStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (decision != RunStatus.APPROVED && decision != RunStatus.REJECTED) {
            return ResponseEntity.badRequest().build();
        }

        String orgId = requireCallerOrgId();

        try {
            String runId = escalationResolveService.resolveEscalationForOrg(escalationId, decision, orgId);
            return ResponseEntity.accepted().body(
                    new EscalationResolveResponse(escalationId, runId, decision.name()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (BusinessValidationException | IllegalArgumentException | IllegalStateException e) {
            // Match sibling ApprovalsController: drop the {error: msg} body — keep the
            // typed response shape; clients should not depend on a sometimes-error-keyed map.
            return ResponseEntity.badRequest().build();
        }
    }

    private static String requireCallerOrgId() {
        String orgId = CallerContext.resolveCallerOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Authenticated principal does not carry an orgId; cannot scope HITL escalations");
        }
        return orgId;
    }
}
