package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.Approval;
import com.operativus.agentmanager.core.model.ApprovalDTO;
import com.operativus.agentmanager.core.model.BulkResolveResponse;
import com.operativus.agentmanager.control.repository.ApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.enums.RunStatus;

import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.exception.StaleDataException;
import com.operativus.agentmanager.core.event.AlertFiredEvent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Domain Responsibility: Manages the Human-in-the-Loop (HITL) manual approval workflows for sensitive agent tool executions.
 * State: Stateless
 */
@Service
public class ApprovalService implements com.operativus.agentmanager.core.registry.ApprovalOperations {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepository approvalRepository;
    private final com.operativus.agentmanager.core.registry.AgentOperations agentOperations;
    private final org.springframework.context.ApplicationContext applicationContext;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager entityManager;

    public ApprovalService(ApprovalRepository approvalRepository, com.operativus.agentmanager.core.registry.AgentOperations agentOperations, org.springframework.context.ApplicationContext applicationContext, org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.approvalRepository = approvalRepository;
        this.agentOperations = agentOperations;
        this.applicationContext = applicationContext;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @summary All PENDING approvals for a specific tenant.
     * @logic Tenant-mandatory; rejects null/blank orgId. The HITL surface has no global
     *        cross-tenant read path — see {@code ApprovalsController}'s class Javadoc.
     */
    public List<ApprovalDTO> getAllPendingApprovals(String orgId) {
        requireOrgId(orgId);
        return approvalRepository.findByStatusAndOrgId(RunStatus.PENDING, orgId).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    /**
     * @summary Paginated PENDING approvals for a specific tenant.
     */
    public org.springframework.data.domain.Page<ApprovalDTO> getAllPendingApprovals(
            String orgId, org.springframework.data.domain.Pageable pageable) {
        requireOrgId(orgId);
        return approvalRepository.findByStatusAndOrgId(RunStatus.PENDING, orgId, pageable).map(this::toDto);
    }

    /**
     * @summary Single approval lookup by id, tenant-scoped (any status).
     * @logic Loads the row and verifies its orgId matches {@code callerOrgId}; cross-tenant
     *        access is surfaced as {@link ResourceNotFoundException} (same exception path as
     *        a missing id) to avoid leaking row existence. Status-agnostic — returns the row
     *        whether PENDING, APPROVED, REJECTED, or anything in between — so audit / history
     *        views can re-fetch a single approval after it has been resolved (the {@code /pending}
     *        list filters by status and drops resolved rows).
     */
    public ApprovalDTO getApprovalForOrg(String approvalId, String callerOrgId) {
        requireOrgId(callerOrgId);
        return approvalRepository.findById(approvalId)
                .filter(a -> callerOrgId.equals(a.getOrgId()))
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Approval", approvalId));
    }

    private static void requireOrgId(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            throw new BusinessValidationException("orgId is required for tenant-scoped HITL operations");
        }
    }

    /**
     * @summary T036(8) — SHA-256 fingerprint over the tool-call payload an approval pins.
     * @logic Concatenation order is fixed ({@code toolName + ":" + toolArguments}) so the
     *   hash is deterministic across processes / instances. UTF-8 byte encoding. Hex output
     *   is 64 chars, matching the {@code VARCHAR(64)} column. Nulls are normalized to "" so
     *   the digest never throws on partial data.
     */
    static String computePayloadHash(String toolName, String toolArguments) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String input = (toolName == null ? "" : toolName) + ":" + (toolArguments == null ? "" : toolArguments);
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required by every JVM; this branch is unreachable on a working JDK.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * @summary Generates a new HITL approval request pausing an agent's run.
     * @logic Instantiates a new Approval entity with a 'PENDING' status, evaluates the DecisionTier, persists the entity to the database, and logs the creation of the HITL request.
     */
    @Transactional
    public ApprovalDTO createApprovalRequest(String runId, String sessionId, String agentId, String toolName, String toolArguments, String message, String requestedBy, String reasoningTrace, String impactAssessment, com.operativus.agentmanager.core.model.DecisionPackage.DecisionTier tier) {
        String workflowRunId = com.operativus.agentmanager.core.callback.AgentContextHolder.getWorkflowRunId();
        
        Approval approval = new Approval(
                UUID.randomUUID().toString(),
                runId,
                workflowRunId,
                sessionId,
                agentId,
                tier == com.operativus.agentmanager.core.model.DecisionPackage.DecisionTier.TIER_1_SAFE ? RunStatus.APPROVED : RunStatus.PENDING, // Auto-Approve Tier 1
                toolName,
                toolArguments,
                requestedBy,
                message,
                tier != null ? tier.name() : "UNKNOWN",
                reasoningTrace,
                impactAssessment
        );
        approval.setOrgId(com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId());
        Approval saved = approvalRepository.save(approval);

        // T036(8) — pin the payload fingerprint at creation. resolveApprovalForOrg
        // re-computes and rejects the transition if anyone has mutated tool_name or
        // tool_arguments between create and resolve.
        //
        // The tool_arguments column is @JdbcTypeCode(SqlTypes.JSON) — Postgres jsonb
        // normalizes whitespace and key order on persist, so hashing the input string
        // here would not match what resolveApprovalForOrg computes from the
        // round-tripped getter value (loaded via findById in a later transaction).
        // We flush + refresh to materialize the normalized form, then compute the
        // hash from getToolArguments() — the same call resolve makes — so both paths
        // converge on identical canonical input. The cost is one extra round-trip per
        // create; the previous unrefreshed hash was effectively broken for any
        // non-trivial JSON payload (multi-key, whitespace, or non-canonical key order).
        approvalRepository.flush();
        entityManager.refresh(saved);
        saved.setPayloadHash(computePayloadHash(saved.getToolName(), saved.getToolArguments()));
        saved = approvalRepository.save(saved);

        log.info("Created new HITL decision record: {} (Tier: {}, Status: {})", saved.getId(), tier, saved.getStatus());
        return toDto(saved);
    }

    /**
     * @summary Processes a human decision (APPROVE/REJECT) on a pending tool call, tenant-scoped.
     * @logic Validates the decision string. Fetches the existing approval AND verifies its
     *        orgId matches {@code callerOrgId}; cross-tenant attempts are surfaced as
     *        ResourceNotFoundException to avoid leaking row existence. Updates the status,
     *        resolver context, and timestamp. Spawns a virtual thread to asynchronously
     *        invoke {@code agentOperations.continueRun()} via a captured AgentContextSnapshot.
     */
    @Transactional
    public ApprovalDTO resolveApprovalForOrg(String approvalId, String decisionStr, String resolvedBy, String callerOrgId) {
        requireOrgId(callerOrgId);
        RunStatus decision = RunStatus.fromValue(decisionStr);
        if (decision != RunStatus.APPROVED && decision != RunStatus.REJECTED) {
            throw new BusinessValidationException("Decision must be either APPROVED or REJECTED");
        }

        return approvalRepository.findById(approvalId)
                .filter(a -> callerOrgId.equals(a.getOrgId()))
                .map(approval -> {
            if (approval.getStatus() != RunStatus.PENDING) {
                throw new BusinessValidationException("Cannot resolve approval in state: " + approval.getStatus());
            }

            // T036(8) — verify the row's payload hash matches what's about to be approved.
            // Skipped when null: pre-changeset-061 rows have no recorded fingerprint and are
            // resolvable as before. New rows always carry a hash so any DB-level mutation
            // of tool_name or tool_arguments between create and resolve is detected here.
            if (approval.getPayloadHash() != null) {
                String recomputed = computePayloadHash(approval.getToolName(), approval.getToolArguments());
                if (!recomputed.equals(approval.getPayloadHash())) {
                    log.error("Payload tamper detected on approval {}: stored hash {} differs from recomputed {} — rejecting resolve",
                            approval.getId(), approval.getPayloadHash(), recomputed);
                    throw new BusinessValidationException(
                            "Approval payload integrity check failed — tool_name or tool_arguments was mutated since creation");
                }
            }

            // T036(4) — quorum logic. APPROVE dedupes against approved_by and stays
            // PENDING until the count reaches approversRequired. REJECT still
            // terminates immediately (single rejector wins by design).
            boolean reachedQuorum = true;
            if (decision == RunStatus.APPROVED) {
                if (approval.getApprovedBy().contains(resolvedBy)) {
                    throw new BusinessValidationException(
                            "Resolver " + resolvedBy + " has already voted APPROVED on this request");
                }
                approval.getApprovedBy().add(resolvedBy);
                reachedQuorum = approval.getApprovedBy().size() >= approval.getApproversRequired();
            }

            if (reachedQuorum) {
                approval.setStatus(decision);
                approval.setResolvedAt(LocalDateTime.now());
            }
            // resolved_by always reflects the most-recent voter so the audit chain
            // stays accurate even mid-quorum; the full vote list lives in approved_by.
            approval.setResolvedBy(resolvedBy);

            try {
                Approval saved = approvalRepository.save(approval);

                if (!reachedQuorum) {
                    // Partial APPROVE: row stays PENDING. No continueRun side effect.
                    log.info("Approval {} partial APPROVE by {} ({}/{} approvers — staying PENDING)",
                            saved.getId(), resolvedBy,
                            saved.getApprovedBy().size(), saved.getApproversRequired());
                    return toDto(saved);
                }

                log.info("Approval {} resolved as {} by {} ({}/{} approvers)",
                        saved.getId(), decision, resolvedBy,
                        saved.getApprovedBy().size(), saved.getApproversRequired());

                // Synchronously resume the agent run via interface injection instead of Modulith events
                log.info("Directly invoking AgentOperations to resume run ID {} given decision {}", saved.getRunId(), decision);
                
                // F12 — fresh VT does NOT inherit JDK 21 ScopedValues. Capture the resolver's
                // AgentContextHolder bindings (orgId, userId) so agentOperations.continueRun and
                // any downstream resumeWorkflowRun see the correct tenant context — otherwise
                // continueRun's strict-orgId AgentRegistry.findById may fail to resolve the agent.
                final com.operativus.agentmanager.core.callback.AgentContextSnapshot resumeSnapshot =
                        com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
                // Fire-and-forget background Virtual Thread to prevent blocking the HTTP response of the approval
                Thread.ofVirtual().start(() -> resumeSnapshot.run(() -> {
                    // Step 1 — resume the agent run. Capture the response (may be null on failure)
                    // so the APPROVED workflow bridge can relay content; failures here MUST NOT
                    // block the workflow_run state cascade in step 2.
                    com.operativus.agentmanager.core.model.RunResponse response = null;
                    try {
                        response = agentOperations.continueRun(saved.getRunId(), decision.name());
                    } catch (Exception e) {
                        log.error("Failed to resume agent run {} post-approval.", saved.getRunId(), e);
                    }

                    // Step 2 — workflow_run state cascade. Tier 2.4 PR 7 F-A pinned the REJECTED
                    // → CANCELLED contract; this block ensures the cascade fires regardless of
                    // step-1 outcome. Pre-fix: the cascade lived inside the same try as
                    // continueRun, so a transient continueRun failure left workflow_run stuck
                    // in PAUSED forever (no automatic recovery path). Each cascade runs in its
                    // own catch so one cascade's failure does not silently mask the other path.
                    if (saved.getWorkflowRunId() != null) {
                        try {
                            if (decision == RunStatus.APPROVED) {
                                log.info("Routing HITL completion response back to WorkflowRun: {}", saved.getWorkflowRunId());
                                String content = response != null ? response.content() : null;
                                applicationContext.getBean(WorkflowService.class)
                                        .resumeWorkflowRun(saved.getWorkflowRunId(), content);
                            } else if (decision == RunStatus.REJECTED) {
                                log.info("Workflow {} halted due to REJECTED HITL approval.", saved.getWorkflowRunId());
                                applicationContext.getBean(WorkflowService.class).cancelWorkflowRun(
                                        saved.getWorkflowRunId(),
                                        "Cancelled: HITL approval " + saved.getId() + " was REJECTED.");
                            }
                        } catch (Exception e) {
                            log.error("Failed to propagate approval {} ({}) to workflow_run {}",
                                    saved.getId(), decision, saved.getWorkflowRunId(), e);
                        }
                    }
                }));
                
                return toDto(saved);
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic locking failure on approval resolution: {}", approvalId);
                throw new StaleDataException("Approval", approvalId);
            }
        }).orElseThrow(() -> new ResourceNotFoundException("Approval", approvalId));
    }

    /**
     * @summary Resolves multiple pending approvals in bulk, tenant-scoped.
     * @logic Each approval is resolved in a separate transaction (the wrapping bean's
     *        proxy intercepts the self-call via {@code applicationContext.getBean(...)})
     *        so a single failure does not roll back successful resolves. Cross-tenant ids
     *        are silently counted as failures — same exception path as missing rows — to
     *        avoid leaking tenant membership.
     */
    @Transactional
    public BulkResolveResponse bulkResolveForOrg(List<String> ids, String decision, String resolvedBy, String callerOrgId) {
        requireOrgId(callerOrgId);
        ApprovalService self = applicationContext.getBean(ApprovalService.class);
        AtomicInteger resolved = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        for (String id : ids) {
            try {
                self.resolveApprovalForOrgInNewTx(id, decision, resolvedBy, callerOrgId);
                resolved.incrementAndGet();
            } catch (Exception e) {
                log.warn("Bulk resolve failed for approval {}: {}", id, e.getMessage());
                failed.incrementAndGet();
            }
        }
        return new BulkResolveResponse(resolved.get(), failed.get());
    }

    /**
     * @summary REQUIRES_NEW variant of {@link #resolveApprovalForOrg} for the bulk path.
     * @logic Without REQUIRES_NEW propagation, a failed inner resolve marks the outer
     *        {@code bulkResolveForOrg} transaction as rollback-only. The outer's catch
     *        block absorbs the exception, but the rollback-only flag remains, and the
     *        outer commit throws {@code UnexpectedRollbackException} → HTTP 500. That
     *        contradicts the documented contract ("a single failure does not roll back
     *        successful resolves") and breaks the BulkResolveResponse counter semantics.
     *        Each bulk-iteration must run in its own committed transaction so the
     *        per-id failure count is meaningful.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public ApprovalDTO resolveApprovalForOrgInNewTx(String approvalId, String decisionStr, String resolvedBy, String callerOrgId) {
        return resolveApprovalForOrg(approvalId, decisionStr, resolvedBy, callerOrgId);
    }

    /**
     * @summary SLA Monitor: Runs every 15 minutes to warn on approvals older than 20 hours.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.approval-sla-check-ms:900000}")
    public void checkApprovalSla() {
        LocalDateTime slaThreshold = LocalDateTime.now().minusHours(20);
        List<Approval> overdue = approvalRepository.findByStatus(RunStatus.PENDING).stream()
                .filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isBefore(slaThreshold))
                .collect(Collectors.toList());
        if (!overdue.isEmpty()) {
            log.warn("SLA WARNING: {} pending approval(s) have exceeded the 20-hour SLA threshold.", overdue.size());
            overdue.forEach(a -> {
                log.warn("  -> Approval {} (agent={}, tool={}) created at {}", a.getId(), a.getAgentId(), a.getToolName(), a.getCreatedAt());
                eventPublisher.publishEvent(new AlertFiredEvent(this, "APPROVAL_SLA_BREACH", a.getId(), "WARNING",
                        "Pending approval " + a.getId() + " has exceeded the 20-hour SLA threshold.", a.getOrgId()));
            });
        }
    }

    /**
     * @summary Garbage Collector: Runs hourly to expire pending approvals older than 24 hours.
     * @logic Calculates a 24-hour cutoff timestamp. Fetches all 'PENDING' approvals created before the cutoff, marks them as 'EXPIRED' by the 'SYSTEM_GC', and saves the updated entities to the database in batch.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.approval-cleanup-ms:3600000}")
    @Transactional
    public void expireStaleApprovals() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<Approval> staleEntries = approvalRepository.findByStatus(RunStatus.PENDING).stream()
                .filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isBefore(cutoff))
                .collect(Collectors.toList());

        for (Approval approval : staleEntries) {
            approval.setStatus(RunStatus.EXPIRED);
            approval.setResolvedAt(LocalDateTime.now());
            approval.setResolvedBy(SecurityPrincipals.SYSTEM_GC);
        }
        
        if (!staleEntries.isEmpty()) {
            approvalRepository.saveAll(staleEntries);
            log.info("Garbage Collector expired {} stale pending approvals.", staleEntries.size());
        }
    }

    /**
     * @summary Maps an internal Approval entity to its external DTO representation.
     * @logic Extracts scalar fields from the JPA entity into the immutable record response.
     */
    private ApprovalDTO toDto(Approval approval) {
        return new ApprovalDTO(
                approval.getId(),
                approval.getRunId(),
                approval.getWorkflowRunId(),
                approval.getAgentId(),
                approval.getToolName(),
                approval.getToolArguments(),
                approval.getStatus(),
                approval.getRequestedBy(),
                approval.getResolvedBy(),
                approval.getContextualMessage(),
                approval.getDecisionTier(),
                approval.getReasoningTrace(),
                approval.getImpactAssessment(),
                approval.getCreatedAt(),
                approval.getResolvedAt()
        );
    }
}
