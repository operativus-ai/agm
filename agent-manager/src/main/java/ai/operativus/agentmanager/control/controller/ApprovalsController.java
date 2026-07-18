package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.security.CallerContext;
import ai.operativus.agentmanager.control.service.ApprovalService;
import ai.operativus.agentmanager.control.service.HumanReviewService;
import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.model.ApprovalDTO;
import ai.operativus.agentmanager.core.model.BulkResolveResponse;
import ai.operativus.agentmanager.core.model.HumanReviewDecideRequest;
import ai.operativus.agentmanager.core.model.HumanReviewDecideResponse;
import ai.operativus.agentmanager.core.model.HumanReviewPendingDTO;
import ai.operativus.agentmanager.core.model.ResolveDecisionRequest;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Exposes REST APIs for retrieving and resolving Human-in-the-Loop
 *     (HITL) approval requests. Tenant scope is resolved exclusively from the authenticated
 *     SecurityContext principal — never from request headers — and every operation is
 *     bounded to that orgId. Cross-tenant reads/writes are not supported on this surface.
 * State: Stateless
 * Dependencies: ApprovalService
 */
@RestController
@RequestMapping("/api/v1/approvals")
@PreAuthorize("isAuthenticated()")
public class ApprovalsController {

    private final ApprovalService approvalService;
    private final HumanReviewService humanReviewService;

    public ApprovalsController(ApprovalService approvalService,
                               HumanReviewService humanReviewService) {
        this.approvalService = approvalService;
        this.humanReviewService = humanReviewService;
    }

    /**
     * @summary Retrieves a paginated list of pending approval requests for the caller's org.
     * @logic Resolves orgId from the authenticated principal; rejects with 400 if the
     *     principal lacks an orgId. Always tenant-scopes the query — there is no global
     *     fallback path.
     */
    @GetMapping("/pending")
    public ResponseEntity<org.springframework.data.domain.Page<ApprovalDTO>> getPendingApprovals(
            @org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        String orgId = requireCallerOrgId();
        return ResponseEntity.ok(approvalService.getAllPendingApprovals(orgId, pageable));
    }

    /**
     * @summary Lists the caller-org's undecided HumanReview pending rows (REQ-HR-5 triage queue).
     * @logic Org-scoped from the authenticated principal. Distinct from {@code /pending} (the
     *     legacy {@link ApprovalDTO} queue) — these are unified HumanReview pauses (workflow-step /
     *     team-member / agent-tool gates) settled via {@code POST /{id}/decide}. Read-only; any
     *     authenticated org member may view, while only ADMIN may decide.
     */
    @GetMapping("/human-review")
    public ResponseEntity<List<HumanReviewPendingDTO>> getPendingHumanReviews() {
        String orgId = requireCallerOrgId();
        return ResponseEntity.ok(humanReviewService.listPending(orgId));
    }

    /**
     * @summary Returns a single approval by id, tenant-scoped (any status).
     * @logic Audit / history views need to re-fetch an approval after it has been resolved.
     *     The {@code /pending} list filters by status, so a resolved approval is no longer
     *     visible there. This endpoint is status-agnostic. Cross-tenant lookups return 404
     *     so tenant-membership cannot be probed.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApprovalDTO> getApproval(@PathVariable("id") String id) {
        String orgId = requireCallerOrgId();
        try {
            return ResponseEntity.ok(approvalService.getApprovalForOrg(id, orgId));
        } catch (ai.operativus.agentmanager.core.exception.ResourceNotFoundException nf) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * @summary Records the human's APPROVE/REJECT decision on a pending HITL request.
     * @logic Tenant-checks the loaded approval; rejects cross-tenant attempts with 404.
     *     `resolvedBy` is the authenticated principal's username — request-body fields
     *     are NOT honored, so a client cannot forge attribution.
     */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApprovalDTO> resolveApproval(
            @PathVariable("id") String id,
            @RequestBody @Valid ResolveDecisionRequest payload) {

        String orgId = requireCallerOrgId();
        String resolvedBy = requireCallerUsername();

        try {
            ApprovalDTO resolved = approvalService.resolveApprovalForOrg(id, payload.decision(), resolvedBy, orgId);
            return ResponseEntity.ok(resolved);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * @summary REQ-HR-5 — unified decide endpoint replacing three scattered
     *     resume paths (legacy {@code /approvals/{id}/resolve},
     *     {@code /runs/{runId}/resume}, {@code /runs/{runId}/continue}).
     *     Operates against {@code human_review_pending} rows; dispatcher
     *     resolves the subject-specific resume action via the
     *     {@code HumanReviewResumeHandler} SPI.
     *
     * @logic Cross-tenant id returns 404 (existence-leak protection §79).
     *     Already-settled rows return their existing decision (idempotent —
     *     second call to /decide is a no-op echo, NOT an error). Decision
     *     dispatch routes by {@code pendingApproval.subjectType}:
     *     <ul>
     *       <li>WORKFLOW_STEP → {@code WorkflowStepResumeHandler} → resume/cancel workflow_run</li>
     *       <li>AGENT_TOOL_CALL → {@code AgentToolResumeHandler} → bridge to legacy ApprovalService</li>
     *       <li>TEAM_MEMBER_DISPATCH → team handler (lands when team orchestrators integrate)</li>
     *     </ul>
     *
     * @return 200 + {@link HumanReviewDecideResponse} carrying the settled
     *     row's metadata (pendingId, runId, subjectType, decision, decidedBy,
     *     decidedAt). Downstream resume action runs asynchronously after the
     *     HTTP response is sent so the operator sees an immediate 200 even
     *     when the resume is heavyweight.
     */
    @PostMapping("/{id}/decide")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<HumanReviewDecideResponse> decide(
            @PathVariable("id") String id,
            @RequestBody @Valid HumanReviewDecideRequest body) {

        String orgId = requireCallerOrgId();
        String decidedBy = requireCallerUsername();

        HumanReviewDecision decision = "approve".equalsIgnoreCase(body.decision())
                ? HumanReviewDecision.APPROVE
                : HumanReviewDecision.REJECT;

        try {
            HumanReviewPending settled = humanReviewService.decide(
                    id, orgId, decision, body.payload(), decidedBy);
            return ResponseEntity.ok(new HumanReviewDecideResponse(
                    settled.getId(),
                    settled.getRunId(),
                    settled.getSubjectType(),
                    settled.getDecision(),
                    settled.getDecidedBy(),
                    settled.getDecidedAt()));
        } catch (ResourceNotFoundException nf) {
            return ResponseEntity.notFound().build();
        }
    }

    record BulkResolveRequest(List<String> ids, String decision) {}

    /**
     * @summary Bulk-resolves multiple approvals with a shared decision, tenant-scoped.
     * @logic Each approval is resolved independently in its own transaction
     *     (Propagation.REQUIRES_NEW at the service layer) so a single failure does not
     *     roll back successful resolves. Cross-tenant ids are counted as failures, not
     *     403'd, so a probe for tenant-membership is not feasible.
     */
    @PostMapping("/bulk-resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkResolveResponse> bulkResolve(@RequestBody BulkResolveRequest req) {
        String orgId = requireCallerOrgId();
        String resolvedBy = requireCallerUsername();
        return ResponseEntity.ok(approvalService.bulkResolveForOrg(req.ids(), req.decision(), resolvedBy, orgId));
    }

    private static String requireCallerOrgId() {
        String orgId = CallerContext.resolveCallerOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Authenticated principal does not carry an orgId; cannot scope HITL approvals");
        }
        return orgId;
    }

    private static String requireCallerUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated principal");
        }
        return auth.getName();
    }
}
