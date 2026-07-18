package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.HumanReviewPendingRepository;
import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.event.HumanReviewDecidedEvent;
import com.operativus.agentmanager.core.event.HumanReviewRequiredEvent;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.HumanReviewPendingDTO;
import com.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import com.operativus.agentmanager.core.model.enums.OnTimeoutPolicy;
import com.operativus.agentmanager.core.spi.HumanReviewResumeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: REQ-HR-2 — the central service that owns the unified
 *     HumanReview pause/decide mechanism. Three entry points:
 *
 *     <ul>
 *       <li>{@link #pauseFor} — invoked by a dispatcher (workflow, agent, team)
 *           to suspend the run pending operator decision. Creates a
 *           {@code human_review_pending} row, audits, and publishes a
 *           {@link HumanReviewRequiredEvent}.</li>
 *       <li>{@link #decide} — invoked by the (future REQ-HR-5)
 *           {@code POST /api/v1/approvals/{id}/decide} endpoint. Settles the
 *           pending row, audits, publishes a {@link HumanReviewDecidedEvent},
 *           and dispatches to the matching {@link HumanReviewResumeHandler}.</li>
 *       <li>{@link #tickTimeout} — {@code @Scheduled} poller. Reads expired
 *           rows and applies the row's {@code on_timeout} policy (AUTO_APPROVE
 *           / AUTO_REJECT / CANCEL).</li>
 *     </ul>
 *
 *     <p>PR-2 ships the service + table + poller. Resume handlers (the SPI's
 *     downstream implementations) land in REQ-HR-3 (workflow) and REQ-HR-4
 *     (agent tool). When no handler is registered for a subject type, the
 *     service logs at WARN and the pending row is still settled — operators
 *     get visibility via the (future, REQ-HR-5) admin list endpoint and can
 *     manually trigger downstream resume by other means.
 *
 * State: Stateless service. Pending state lives in
 *     {@code human_review_pending} table; transient state (handler registry)
 *     is built once at construction from injected SPI implementations.
 */
@Service
public class HumanReviewService {

    private static final Logger log = LoggerFactory.getLogger(HumanReviewService.class);
    private static final String AUDIT_RESOURCE_TYPE = "human_review";

    private final HumanReviewPendingRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final SystemAuditService systemAuditService;
    private final Map<HumanReviewSubjectType, HumanReviewResumeHandler> handlersByType;

    public HumanReviewService(HumanReviewPendingRepository repository,
                              ApplicationEventPublisher eventPublisher,
                              SystemAuditService systemAuditService,
                              List<HumanReviewResumeHandler> handlers) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.systemAuditService = systemAuditService;
        List<HumanReviewResumeHandler> list = handlers != null ? handlers : List.of();
        this.handlersByType = list.stream().collect(Collectors.toMap(
                HumanReviewResumeHandler::subjectType,
                Function.identity(),
                (a, b) -> a));
    }

    /**
     * REQ-HR-5 — lists the org's undecided HumanReview pending rows, newest first, for the
     * operator triage surface. Tenant-scoped; decided rows are filtered out (this is a
     * work-queue view, not a history view). Read-only.
     */
    @Transactional(readOnly = true)
    public List<HumanReviewPendingDTO> listPending(String orgId) {
        return repository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .filter(row -> row.getDecision() == null)
                .map(row -> new HumanReviewPendingDTO(
                        row.getId(),
                        row.getRunId(),
                        row.getSubjectType(),
                        row.getSubjectId(),
                        row.getReason(),
                        row.getOptions(),
                        row.getCreatedAt(),
                        row.getExpiresAt()))
                .toList();
    }

    /**
     * REQ-HR-2 timeout-poller tick interval. Defaults to 30s; tunable per
     * environment via {@code agm.humanreview.timeout-tick-seconds}. The poller
     * only runs in environments where Spring's scheduler is enabled (which it
     * is by default in {@code application.properties}).
     */
    @Value("${agm.humanreview.timeout-tick-seconds:30}")
    private long timeoutTickSeconds;

    /**
     * Create a new pending review row and notify subscribers. The dispatcher
     * that calls this is responsible for atomically setting the run's status
     * to {@code AWAITING_HUMAN_REVIEW} (or legacy equivalent during the
     * migration window) BEFORE invoking — the service writes the pending row
     * but does NOT mutate the run row.
     *
     * @param subjectType subject discriminator (drives later resume dispatch)
     * @param subjectId   id of the workflow_step / agent_tool_call / team_member
     * @param runId       id of the originating run (workflow_run / agent_run / team_run)
     * @param orgId       caller's org for tenant scoping
     * @param reason      human-readable reason; carries to SSE event
     * @param config      the HumanReview struct that drove this pause; persisted
     *                    so the timeout poller can read on_timeout without re-
     *                    fetching the originating row
     * @param extraOptions optional per-pause context (e.g. CONDITION's evaluated input)
     * @param requestedBy username of the caller-thread principal (for audit)
     * @return the persisted {@link HumanReviewPending} row (with id assigned)
     */
    @Transactional
    public HumanReviewPending pauseFor(HumanReviewSubjectType subjectType,
                                       String subjectId,
                                       String runId,
                                       String orgId,
                                       String reason,
                                       HumanReview config,
                                       Map<String, Object> extraOptions,
                                       String requestedBy) {
        return pauseForWithId(UUID.randomUUID().toString(), subjectType, subjectId, runId, orgId,
                reason, config, extraOptions, requestedBy);
    }

    /**
     * REQ-HR-4.5 — variant of {@link #pauseFor} that accepts a caller-provided id.
     * Used by {@code HitlAdvisor} dual-tracking so the {@code human_review_pending.id}
     * matches the legacy {@code approvals.id}, letting {@code AgentToolResumeHandler}
     * bridge decisions back to {@code ApprovalService.resolveApprovalForOrg} by the
     * same identifier. Behavior is otherwise identical to {@link #pauseFor}.
     */
    @Transactional
    public HumanReviewPending pauseForWithId(String pendingId,
                                             HumanReviewSubjectType subjectType,
                                             String subjectId,
                                             String runId,
                                             String orgId,
                                             String reason,
                                             HumanReview config,
                                             Map<String, Object> extraOptions,
                                             String requestedBy) {
        HumanReviewPending row = new HumanReviewPending();
        row.setId(pendingId);
        row.setRunId(runId);
        row.setSubjectType(subjectType.name());
        row.setSubjectId(subjectId);
        row.setOrgId(orgId);
        row.setReason(reason);
        row.setCreatedAt(Instant.now());

        Map<String, Object> options = new HashMap<>();
        if (config != null) {
            options.put("requiresConfirmation", config.requiresConfirmation());
            options.put("requiresUserInput", config.requiresUserInput());
            options.put("requiresOutputReview", config.requiresOutputReview());
            options.put("onReject", config.effectiveOnReject().name());
            options.put("onTimeout", config.effectiveOnTimeout().name());
            options.put("onError", config.effectiveOnError().name());
            options.put("approvers", config.approvers());
            options.put("elseStepId", config.elseStepId());
            if (config.timeoutSeconds() != null && config.timeoutSeconds() > 0L) {
                row.setExpiresAt(row.getCreatedAt().plusSeconds(config.timeoutSeconds()));
            }
        }
        if (extraOptions != null) options.putAll(extraOptions);
        row.setOptions(options);

        HumanReviewPending saved = repository.save(row);
        systemAuditService.record(orgId, requestedBy, "HUMAN_REVIEW_PAUSED",
                AUDIT_RESOURCE_TYPE, saved.getId(),
                "POST", "/api/v1/approvals/" + saved.getId() + "/decide", 200);
        eventPublisher.publishEvent(new HumanReviewRequiredEvent(saved));
        log.info("HumanReview paused: id={}, subjectType={}, subjectId={}, runId={}, expiresAt={}",
                saved.getId(), saved.getSubjectType(), saved.getSubjectId(), saved.getRunId(),
                saved.getExpiresAt());
        return saved;
    }

    /**
     * Apply an operator decision to a pending row. Cross-tenant access surfaces
     * as 404 (existence-leak protection §79) — caller passes their org; row is
     * looked up via {@code findByIdAndOrgId}.
     *
     * @throws ResourceNotFoundException when the id doesn't exist OR belongs to
     *     a different tenant (single 404 for both per existence-leak rule)
     * @throws IllegalStateException when the row is already settled (idempotency
     *     handled at the endpoint layer — see REQ-HR-5; second-call returns 200)
     */
    @Transactional
    public HumanReviewPending decide(String id,
                                     String callerOrgId,
                                     HumanReviewDecision decision,
                                     Map<String, Object> payload,
                                     String decidedBy) {
        HumanReviewPending row = repository.findByIdAndOrgId(id, callerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("HumanReviewPending", id));
        if (row.getDecision() != null) {
            log.warn("HumanReview decide called on already-settled row: id={}, existing={}, requested={}",
                    id, row.getDecision(), decision);
            return row;
        }
        row.setDecision(decision.name());
        row.setDecidedAt(Instant.now());
        row.setDecidedBy(decidedBy);
        if (payload != null) row.setPayload(payload);
        HumanReviewPending saved = repository.save(row);

        systemAuditService.record(callerOrgId, decidedBy, "HUMAN_REVIEW_DECIDED",
                AUDIT_RESOURCE_TYPE, saved.getId(),
                "POST", "/api/v1/approvals/" + saved.getId() + "/decide", 200);
        eventPublisher.publishEvent(new HumanReviewDecidedEvent(saved, decision));

        dispatchToHandler(saved, decision);
        return saved;
    }

    /**
     * Scheduled poller — applies the row's {@code on_timeout} policy to any
     * undecided row whose {@code expires_at} has passed. Runs on Spring's
     * fixed-delay scheduler; interval is {@code agm.humanreview.timeout-tick-seconds}.
     *
     * <p>Each expired row is settled in its own transaction (delegated to
     * {@link #decide}) so a malformed row doesn't poison the whole batch.
     */
    @Scheduled(fixedDelayString = "${agm.humanreview.timeout-tick-millis:30000}")
    public void tickTimeout() {
        List<HumanReviewPending> expired = repository.findExpired(Instant.now());
        if (expired.isEmpty()) return;
        log.info("HumanReview timeout poller: {} expired row(s) to settle", expired.size());
        for (HumanReviewPending row : expired) {
            try {
                OnTimeoutPolicy policy = readOnTimeoutPolicy(row);
                HumanReviewDecision auto = switch (policy) {
                    case AUTO_APPROVE -> HumanReviewDecision.AUTO_APPROVED;
                    case AUTO_REJECT -> HumanReviewDecision.AUTO_REJECTED;
                    case CANCEL -> HumanReviewDecision.CANCELLED;
                };
                decide(row.getId(), row.getOrgId(), auto, null, "timeout-poller");
            } catch (RuntimeException e) {
                log.warn("HumanReview timeout settle failed for id={}: {}", row.getId(), e.toString());
            }
        }
    }

    private OnTimeoutPolicy readOnTimeoutPolicy(HumanReviewPending row) {
        Map<String, Object> options = row.getOptions();
        if (options == null) return OnTimeoutPolicy.AUTO_REJECT;
        Object raw = options.get("onTimeout");
        return raw == null ? OnTimeoutPolicy.AUTO_REJECT : OnTimeoutPolicy.fromString(raw.toString());
    }

    private void dispatchToHandler(HumanReviewPending row, HumanReviewDecision decision) {
        HumanReviewSubjectType type = HumanReviewSubjectType.fromString(row.getSubjectType());
        if (type == null) {
            log.warn("HumanReview decided but subject_type '{}' is unknown — no handler dispatch", row.getSubjectType());
            return;
        }
        HumanReviewResumeHandler handler = handlersByType.get(type);
        if (handler == null) {
            // Expected during the REQ-HR-2..6 migration window — handlers
            // ship in REQ-HR-3 (WORKFLOW_STEP) + REQ-HR-4 (AGENT_TOOL_CALL).
            log.warn("HumanReview decided but no handler registered for subjectType={} (id={}); "
                    + "downstream resume must be triggered by other means", type, row.getId());
            return;
        }
        try {
            handler.onDecided(row, decision);
        } catch (RuntimeException e) {
            log.error("HumanReview handler {} threw for id={}: {}",
                    handler.getClass().getSimpleName(), row.getId(), e.toString(), e);
            // Don't rethrow — the pending row is already settled. The handler
            // failure is an audit-level concern; operators can re-trigger via
            // future admin endpoint or manual run state inspection.
        }
    }
}
