package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.HumanReviewPendingRepository;
import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.event.HumanReviewDecidedEvent;
import com.operativus.agentmanager.core.event.HumanReviewRequiredEvent;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import com.operativus.agentmanager.core.model.enums.OnErrorPolicy;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import com.operativus.agentmanager.core.model.enums.OnTimeoutPolicy;
import com.operativus.agentmanager.core.spi.HumanReviewResumeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link HumanReviewService} contracts at unit-level: pauseFor persistence
 * + event emission + audit, decide settlement + handler dispatch + idempotency,
 * timeout poller policy application + per-row failure isolation.
 */
@ExtendWith(MockitoExtension.class)
class HumanReviewServiceTest {

    @Mock private HumanReviewPendingRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SystemAuditService systemAuditService;
    @Mock private HumanReviewResumeHandler workflowHandler;
    @Mock private HumanReviewResumeHandler agentHandler;

    private HumanReviewService service;

    @BeforeEach
    void setUp() {
        when(workflowHandler.subjectType()).thenReturn(HumanReviewSubjectType.WORKFLOW_STEP);
        when(agentHandler.subjectType()).thenReturn(HumanReviewSubjectType.AGENT_TOOL_CALL);
        service = new HumanReviewService(repository, eventPublisher, systemAuditService,
                List.of(workflowHandler, agentHandler));
    }

    // --- pauseFor ---

    @Test
    void pauseFor_minimalConfig_persistsRowAndPublishesEvent() {
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));
        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);

        HumanReviewPending result = service.pauseFor(
                HumanReviewSubjectType.WORKFLOW_STEP, "step-1", "wfrun-1",
                "org-1", "condition false", hr, null, "alice");

        assertNotNull(result.getId());
        assertEquals("WORKFLOW_STEP", result.getSubjectType());
        assertEquals("step-1", result.getSubjectId());
        assertEquals("wfrun-1", result.getRunId());
        assertEquals("org-1", result.getOrgId());
        assertNull(result.getExpiresAt(), "null timeoutSeconds → no expires_at");
        assertNull(result.getDecision());

        verify(repository).save(any(HumanReviewPending.class));
        verify(eventPublisher).publishEvent(any(HumanReviewRequiredEvent.class));
        verify(systemAuditService).record(eq("org-1"), eq("alice"),
                eq("HUMAN_REVIEW_PAUSED"), eq("human_review"), eq(result.getId()),
                eq("POST"), any(), eq((Integer) 200));
    }

    @Test
    void pauseFor_withTimeoutSeconds_setsExpiresAt() {
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));
        HumanReview hr = new HumanReview(true, null, null, null, null, null, 300L, null, null);

        Instant before = Instant.now();
        HumanReviewPending result = service.pauseFor(
                HumanReviewSubjectType.WORKFLOW_STEP, "step-1", "wfrun-1",
                "org-1", "with timeout", hr, null, "alice");
        Instant after = Instant.now();

        assertNotNull(result.getExpiresAt());
        // expires_at = created_at + 300s
        assertTrue(result.getExpiresAt().isAfter(before.plusSeconds(299)),
                "expires_at should be > before + 299s; got: " + result.getExpiresAt());
        assertTrue(result.getExpiresAt().isBefore(after.plusSeconds(301)),
                "expires_at should be < after + 301s; got: " + result.getExpiresAt());
    }

    @Test
    void pauseFor_serializesOnTimeoutIntoOptions() {
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));
        HumanReview hr = new HumanReview(true, null, null,
                OnRejectPolicy.SKIP, OnTimeoutPolicy.AUTO_APPROVE, OnErrorPolicy.RETRY,
                60L, List.of("alice", "bob"), null);

        HumanReviewPending result = service.pauseFor(
                HumanReviewSubjectType.WORKFLOW_STEP, "step-1", "wfrun-1",
                "org-1", "rich config", hr, Map.of("priorInput", "halt"), "alice");

        assertEquals("AUTO_APPROVE", result.getOptions().get("onTimeout"));
        assertEquals("SKIP", result.getOptions().get("onReject"));
        assertEquals("RETRY", result.getOptions().get("onError"));
        assertEquals(List.of("alice", "bob"), result.getOptions().get("approvers"));
        assertEquals("halt", result.getOptions().get("priorInput"));
    }

    @Test
    void pauseFor_nullConfig_persistsWithoutExpiry() {
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));

        HumanReviewPending result = service.pauseFor(
                HumanReviewSubjectType.AGENT_TOOL_CALL, "tool-call-1", "agentrun-1",
                "org-1", "no config", null, null, "alice");

        assertNull(result.getExpiresAt());
        assertTrue(result.getOptions() == null || result.getOptions().isEmpty(),
                "null config + null extras → empty/null options map");
    }

    // --- decide ---

    @Test
    void decide_approve_settlesRowAndDispatchesHandler() {
        HumanReviewPending row = makePending("WORKFLOW_STEP", "step-1", "org-1");
        when(repository.findByIdAndOrgId(row.getId(), "org-1")).thenReturn(Optional.of(row));
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));

        HumanReviewPending result = service.decide(row.getId(), "org-1",
                HumanReviewDecision.APPROVE, Map.of("output", "ok"), "alice");

        assertEquals("APPROVE", result.getDecision());
        assertEquals("alice", result.getDecidedBy());
        assertNotNull(result.getDecidedAt());
        assertEquals(Map.of("output", "ok"), result.getPayload());
        verify(workflowHandler).onDecided(eq(result), eq(HumanReviewDecision.APPROVE));
        verify(eventPublisher).publishEvent(any(HumanReviewDecidedEvent.class));
        verify(systemAuditService).record(eq("org-1"), eq("alice"),
                eq("HUMAN_REVIEW_DECIDED"), eq("human_review"), eq(row.getId()),
                eq("POST"), any(), eq((Integer) 200));
    }

    @Test
    void decide_reject_routesToRejectHandler() {
        HumanReviewPending row = makePending("AGENT_TOOL_CALL", "tool-1", "org-1");
        when(repository.findByIdAndOrgId(row.getId(), "org-1")).thenReturn(Optional.of(row));
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));

        service.decide(row.getId(), "org-1", HumanReviewDecision.REJECT, null, "alice");

        verify(agentHandler).onDecided(any(), eq(HumanReviewDecision.REJECT));
        verify(workflowHandler, never()).onDecided(any(), any());
    }

    @Test
    void decide_crossTenant_throwsResourceNotFound() {
        HumanReviewPending row = makePending("WORKFLOW_STEP", "step-1", "org-1");
        when(repository.findByIdAndOrgId(row.getId(), "org-OTHER")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.decide(
                row.getId(), "org-OTHER", HumanReviewDecision.APPROVE, null, "intruder"));
        verify(workflowHandler, never()).onDecided(any(), any());
    }

    @Test
    void decide_alreadySettled_isIdempotentNoOp() {
        HumanReviewPending row = makePending("WORKFLOW_STEP", "step-1", "org-1");
        row.setDecision("APPROVE");
        row.setDecidedAt(Instant.now());
        when(repository.findByIdAndOrgId(row.getId(), "org-1")).thenReturn(Optional.of(row));

        HumanReviewPending result = service.decide(row.getId(), "org-1",
                HumanReviewDecision.REJECT, null, "alice");

        // Second call returns the existing settled row without overwriting.
        assertEquals("APPROVE", result.getDecision());
        verify(repository, never()).save(any());
        verify(workflowHandler, never()).onDecided(any(), any());
    }

    @Test
    void decide_noHandlerForSubjectType_settlesRowAndLogsWarn() {
        HumanReviewPending row = makePending("TEAM_MEMBER_DISPATCH", "member-1", "org-1");
        when(repository.findByIdAndOrgId(row.getId(), "org-1")).thenReturn(Optional.of(row));
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));

        HumanReviewPending result = service.decide(row.getId(), "org-1",
                HumanReviewDecision.APPROVE, null, "alice");

        // Row still settles; just no downstream dispatch.
        assertEquals("APPROVE", result.getDecision());
        verify(workflowHandler, never()).onDecided(any(), any());
        verify(agentHandler, never()).onDecided(any(), any());
    }

    @Test
    void decide_handlerThrows_doesNotRollbackSettlement() {
        HumanReviewPending row = makePending("WORKFLOW_STEP", "step-1", "org-1");
        when(repository.findByIdAndOrgId(row.getId(), "org-1")).thenReturn(Optional.of(row));
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("handler boom"))
                .when(workflowHandler).onDecided(any(), any());

        HumanReviewPending result = service.decide(row.getId(), "org-1",
                HumanReviewDecision.APPROVE, null, "alice");

        // Row is still settled even though the handler threw — failure is logged.
        assertEquals("APPROVE", result.getDecision());
    }

    // --- tickTimeout ---

    @Test
    void tickTimeout_appliesAutoApprovePolicyFromOptions() {
        HumanReviewPending expired = makeExpired("WORKFLOW_STEP", "step-1", "org-1", "AUTO_APPROVE");
        when(repository.findExpired(any(Instant.class))).thenReturn(List.of(expired));
        when(repository.findByIdAndOrgId(expired.getId(), "org-1")).thenReturn(Optional.of(expired));
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));

        service.tickTimeout();

        ArgumentCaptor<HumanReviewDecidedEvent> evt = ArgumentCaptor.forClass(HumanReviewDecidedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertEquals(HumanReviewDecision.AUTO_APPROVED, evt.getValue().decision());
        verify(workflowHandler).onDecided(any(), eq(HumanReviewDecision.AUTO_APPROVED));
    }

    @Test
    void tickTimeout_appliesAutoRejectByDefault() {
        // Row with options missing the onTimeout key → defaults to AUTO_REJECT
        // per HumanReviewService.readOnTimeoutPolicy.
        HumanReviewPending expired = makeExpired("WORKFLOW_STEP", "step-1", "org-1", null);
        when(repository.findExpired(any(Instant.class))).thenReturn(List.of(expired));
        when(repository.findByIdAndOrgId(expired.getId(), "org-1")).thenReturn(Optional.of(expired));
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));

        service.tickTimeout();

        verify(workflowHandler).onDecided(any(), eq(HumanReviewDecision.AUTO_REJECTED));
    }

    @Test
    void tickTimeout_perRowFailureIsolated() {
        HumanReviewPending bad = makeExpired("WORKFLOW_STEP", "step-bad", "org-1", "AUTO_REJECT");
        HumanReviewPending good = makeExpired("WORKFLOW_STEP", "step-good", "org-1", "AUTO_REJECT");
        when(repository.findExpired(any(Instant.class))).thenReturn(List.of(bad, good));
        when(repository.findByIdAndOrgId(bad.getId(), "org-1"))
                .thenThrow(new RuntimeException("simulated"));
        when(repository.findByIdAndOrgId(good.getId(), "org-1")).thenReturn(Optional.of(good));
        when(repository.save(any(HumanReviewPending.class))).thenAnswer(inv -> inv.getArgument(0));

        service.tickTimeout();

        // good still settled despite bad throwing.
        verify(workflowHandler, times(1)).onDecided(any(), eq(HumanReviewDecision.AUTO_REJECTED));
    }

    @Test
    void tickTimeout_noExpiredRows_noOp() {
        when(repository.findExpired(any(Instant.class))).thenReturn(List.of());

        service.tickTimeout();

        verify(eventPublisher, never()).publishEvent(any());
        verify(workflowHandler, never()).onDecided(any(), any());
    }

    // --- helpers ---

    private static HumanReviewPending makePending(String subjectType, String subjectId, String orgId) {
        HumanReviewPending p = new HumanReviewPending();
        p.setId("hrp-" + java.util.UUID.randomUUID());
        p.setSubjectType(subjectType);
        p.setSubjectId(subjectId);
        p.setRunId("run-" + subjectId);
        p.setOrgId(orgId);
        p.setCreatedAt(Instant.now());
        return p;
    }

    private static HumanReviewPending makeExpired(String subjectType, String subjectId, String orgId, String onTimeout) {
        HumanReviewPending p = makePending(subjectType, subjectId, orgId);
        p.setExpiresAt(Instant.now().minusSeconds(60));
        if (onTimeout != null) {
            p.setOptions(Map.of("onTimeout", onTimeout));
        } else {
            p.setOptions(Map.of()); // no onTimeout key → defaults to AUTO_REJECT
        }
        return p;
    }
}
