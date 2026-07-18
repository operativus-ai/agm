package ai.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.UserRepository;
import ai.operativus.agentmanager.control.service.ComplianceExportService;
import ai.operativus.agentmanager.control.service.ErasureOrchestrationService;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.control.service.queue.ErasureRequestJobHandler;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.ErasureRequest;
import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GDPR/CCPA Compliance API.
 * Art. 15 + Art. 20: data export.
 * Art. 17: right to erasure via durable ErasureRequest lifecycle.
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private static final Logger log = LoggerFactory.getLogger(ComplianceController.class);

    private final ComplianceExportService complianceExportService;
    private final ErasureOrchestrationService erasureOrchestrationService;
    private final PersistentJobQueueService jobQueueService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    public ComplianceController(ComplianceExportService complianceExportService,
                                ErasureOrchestrationService erasureOrchestrationService,
                                PersistentJobQueueService jobQueueService,
                                ObjectMapper objectMapper,
                                UserRepository userRepository) {
        this.complianceExportService = complianceExportService;
        this.erasureOrchestrationService = erasureOrchestrationService;
        this.jobQueueService = jobQueueService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    /**
     * Cross-tenant authz gate. SpEL on each endpoint filters by role; this gate verifies
     * the target user lives in the caller's org (or is the caller themselves). Returns 404
     * via {@link ResourceNotFoundException} on mismatch — not 403 — to avoid leaking
     * which usernames exist in foreign orgs. Matches the convention used by
     * tenant-scoped agent/workflow/KB endpoints.
     */
    private void requireSameOrgOrSelf(String targetUserId, Authentication auth) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new ResourceNotFoundException("User", String.valueOf(targetUserId));
        }
        if (auth != null && targetUserId.equals(auth.getName())) {
            return; // self-access — always allowed (GDPR Art. 15 self-portability)
        }
        User target = userRepository.findByUsername(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));
        String callerOrgId = AgentContextHolder.getOrgId();
        if (callerOrgId == null || !callerOrgId.equals(target.getOrgId())) {
            // Existence-leak protection — a foreign-org admin should not be able to
            // distinguish "user exists in other org" from "user does not exist."
            throw new ResourceNotFoundException("User", targetUserId);
        }
    }

    /**
     * GDPR Art. 15 + Art. 20: Export all user data as portable JSON.
     */
    @GetMapping("/export/{userId}")
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")
    public ResponseEntity<Map<String, Object>> exportUserData(@PathVariable String userId, Authentication authentication) {
        log.info("GDPR data export requested for userId={}", userId);
        requireSameOrgOrSelf(userId, authentication);
        return ResponseEntity.ok(complianceExportService.exportUserData(userId));
    }

    /**
     * GDPR Art. 17: Submit a durable erasure request and process it synchronously.
     * Returns the ErasureRequest record (id, status, summary) for audit purposes.
     */
    @PostMapping("/erasure-requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> submitErasureRequest(
            @RequestParam String userId,
            Authentication authentication) throws Exception {
        requireSameOrgOrSelf(userId, authentication);
        // Bug #50 — capture orgId on the request thread (where AgentContextHolder is bound
        // by TenantContextFilter) and carry it through to the worker so the erasure_requests
        // row is stamped with the caller's tenant.
        String callerOrgId = AgentContextHolder.getOrgId();
        log.warn("GDPR erasure request submitted for userId={} by {} (orgId={})", userId, authentication.getName(), callerOrgId);
        String payload = objectMapper.writeValueAsString(
                new ErasureRequestJobHandler.Payload(userId, authentication.getName(), callerOrgId));
        var job = jobQueueService.enqueue(ErasureRequestJobHandler.JOB_TYPE, null, payload, "HIGH", null);
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    /**
     * List all erasure requests for a given user (audit trail).
     */
    @GetMapping("/erasure-requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ErasureRequest>> listErasureRequests(
            @RequestParam String userId,
            Authentication authentication) {
        requireSameOrgOrSelf(userId, authentication);
        // Bug #50 — scope by caller orgId so an admin only sees their own tenant's
        // erasure history. Legacy rows with NULL org_id are filtered out by the
        // repository finder; backfill is a separate deploy concern.
        return ResponseEntity.ok(erasureOrchestrationService.findByUserId(userId, AgentContextHolder.getOrgId()));
    }

    /**
     * @deprecated Use POST /erasure-requests instead.
     * Kept for backward compatibility — delegates to the new orchestration service.
     */
    @DeleteMapping("/erase/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> eraseUserData(
            @PathVariable String userId,
            Authentication authentication) {
        log.warn("GDPR data erasure (legacy endpoint) requested for userId={}", userId);
        requireSameOrgOrSelf(userId, authentication);
        ErasureRequest result = erasureOrchestrationService.submitAndProcess(
                userId, authentication.getName(), AgentContextHolder.getOrgId());
        Map<String, Object> summary = result.getSummary() != null ? result.getSummary() : Map.of("status", result.getStatus().name());
        return ResponseEntity.ok(summary);
    }
}
