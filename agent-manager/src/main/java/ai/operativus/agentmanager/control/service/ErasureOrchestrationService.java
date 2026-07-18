package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.ErasureRequestRepository;
import ai.operativus.agentmanager.control.service.erasure.ErasureHandler;
import ai.operativus.agentmanager.core.entity.ErasureRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GDPR Article 17 — Right to Erasure.
 * Orchestrates per-domain handlers under a single durable ErasureRequest record.
 * The request persists through failure so ops can inspect and retry partial erasures.
 */
@Service
public class ErasureOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ErasureOrchestrationService.class);

    private final ErasureRequestRepository requestRepository;
    private final List<ErasureHandler> handlers;

    public ErasureOrchestrationService(ErasureRequestRepository requestRepository,
                                       List<ErasureHandler> handlers) {
        this.requestRepository = requestRepository;
        this.handlers = handlers;
    }

    /**
     * Backwards-compatible overload — orgId defaults to null. Prefer the 3-arg
     * variant so the {@code erasure_requests} row carries the caller's tenant
     * for Bug #50 scoped reads.
     */
    @Transactional
    public ErasureRequest submitAndProcess(String userId, String requestedBy) {
        return submitAndProcess(userId, requestedBy, null);
    }

    /**
     * Tenant-stamped variant — Bug #50. The orgId is denormalized onto the
     * erasure_requests row so {@link #findByUserId(String, String)} can scope
     * reads without joining users.
     */
    @Transactional
    public ErasureRequest submitAndProcess(String userId, String requestedBy, String orgId) {
        ErasureRequest request = new ErasureRequest();
        request.setUserId(userId);
        request.setOrgId(orgId);
        request.setRequestedBy(requestedBy);
        request.setStatus(ErasureRequest.Status.PENDING);
        request = requestRepository.save(request);

        return process(request);
    }

    /**
     * Processes a PENDING ErasureRequest by running all domain handlers in sequence.
     * Records per-domain counts in the summary; marks COMPLETED, PARTIAL, or FAILED.
     */
    @Transactional
    public ErasureRequest process(ErasureRequest request) {
        String userId = request.getUserId();
        log.warn("GDPR erasure started — requestId={} userId={}", request.getId(), userId);

        request.setStatus(ErasureRequest.Status.IN_PROGRESS);
        request.setStartedAt(LocalDateTime.now());
        request = requestRepository.save(request);

        Map<String, Object> summary = new LinkedHashMap<>();
        int failures = 0;

        for (ErasureHandler handler : handlers) {
            try {
                int count = handler.erase(userId);
                summary.put(handler.domain(), count);
                log.info("Erasure handler '{}' completed — {} records affected for userId={}", handler.domain(), count, userId);
            } catch (Exception e) {
                log.error("Erasure handler '{}' failed for userId={}: {}", handler.domain(), userId, e.getMessage(), e);
                summary.put(handler.domain() + "_error", e.getMessage());
                failures++;
            }
        }

        request.setSummary(summary);
        request.setCompletedAt(LocalDateTime.now());

        if (failures == 0) {
            request.setStatus(ErasureRequest.Status.COMPLETED);
        } else if (failures < handlers.size()) {
            request.setStatus(ErasureRequest.Status.PARTIAL);
            request.setErrorMessage(failures + " domain handler(s) failed — see summary for details");
        } else {
            request.setStatus(ErasureRequest.Status.FAILED);
            request.setErrorMessage("All domain handlers failed");
        }

        request = requestRepository.save(request);
        log.warn("GDPR erasure finished — requestId={} status={} userId={}", request.getId(), request.getStatus(), userId);
        return request;
    }

    /**
     * Unscoped lookup — system / admin-tooling callers only. Most callers should
     * use {@link #findByUserId(String, String)} below.
     */
    public List<ErasureRequest> findByUserId(String userId) {
        return requestRepository.findByUserIdOrderByRequestedAtDesc(userId);
    }

    /**
     * Tenant-scoped lookup — Bug #50. Legacy rows with NULL org_id are filtered
     * out (a foreign-org admin shouldn't see them either; backfill is a separate
     * deploy-time concern).
     */
    public List<ErasureRequest> findByUserId(String userId, String orgId) {
        return requestRepository.findByUserIdAndOrgIdOrderByRequestedAtDesc(userId, orgId);
    }
}
