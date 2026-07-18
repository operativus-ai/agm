package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AgentToolResumeHandler}'s bridge contract:
 * - declares AGENT_TOOL_CALL as its subject type (drives HumanReviewService dispatch)
 * - APPROVE/AUTO_APPROVED → resolveApprovalForOrg("APPROVED", ...) (legacy RunStatus name)
 * - REJECT/AUTO_REJECTED/CANCELLED → resolveApprovalForOrg("REJECTED", ...) (legacy RunStatus name)
 * - legacy ApprovalService failure does NOT escape (swallowed + logged) — expected
 *   during the REQ-HR-4/4.5 migration window when the pending row may not yet
 *   have a corresponding legacy Approval row.
 */
@ExtendWith(MockitoExtension.class)
class AgentToolResumeHandlerTest {

    @Mock private ApplicationContext applicationContext;
    @Mock private ApprovalService approvalService;
    @Mock private PlatformTransactionManager txManager;

    private AgentToolResumeHandler handler;

    @BeforeEach
    void setUp() {
        // TransactionTemplate calls txManager.getTransaction(...) before invoking the callback;
        // return a no-op TransactionStatus so the callback runs and exceptions still propagate
        // (the production code's catch is what swallows them).
        org.mockito.Mockito.lenient()
                .when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        handler = new AgentToolResumeHandler(applicationContext, txManager);
    }

    @Test
    void subjectType_isAgentToolCall() {
        assertEquals(HumanReviewSubjectType.AGENT_TOOL_CALL, handler.subjectType());
    }

    @Test
    void onDecided_approve_resolvesLegacyApprovalWithApprove() {
        when(applicationContext.getBean(ApprovalService.class)).thenReturn(approvalService);
        HumanReviewPending p = pending("appr-1", "org-1", "alice");

        handler.onDecided(p, HumanReviewDecision.APPROVE);

        verify(approvalService).resolveApprovalForOrg(eq("appr-1"), eq("APPROVED"), eq("alice"), eq("org-1"));
    }

    @Test
    void onDecided_autoApproved_resolvesAsApprove() {
        when(applicationContext.getBean(ApprovalService.class)).thenReturn(approvalService);
        HumanReviewPending p = pending("appr-2", "org-1", "timeout-poller");

        handler.onDecided(p, HumanReviewDecision.AUTO_APPROVED);

        verify(approvalService).resolveApprovalForOrg(eq("appr-2"), eq("APPROVED"), eq("timeout-poller"), eq("org-1"));
    }

    @Test
    void onDecided_reject_resolvesLegacyApprovalWithReject() {
        when(applicationContext.getBean(ApprovalService.class)).thenReturn(approvalService);
        HumanReviewPending p = pending("appr-3", "org-1", "bob");

        handler.onDecided(p, HumanReviewDecision.REJECT);

        verify(approvalService).resolveApprovalForOrg(eq("appr-3"), eq("REJECTED"), eq("bob"), eq("org-1"));
    }

    @Test
    void onDecided_cancelled_resolvesAsReject() {
        when(applicationContext.getBean(ApprovalService.class)).thenReturn(approvalService);
        HumanReviewPending p = pending("appr-4", "org-1", "system");

        handler.onDecided(p, HumanReviewDecision.CANCELLED);

        verify(approvalService).resolveApprovalForOrg(eq("appr-4"), eq("REJECTED"), eq("system"), eq("org-1"));
    }

    @Test
    void onDecided_decidedByNull_fallsBackToBridgeIdentity() {
        when(applicationContext.getBean(ApprovalService.class)).thenReturn(approvalService);
        HumanReviewPending p = pending("appr-5", "org-1", null);

        handler.onDecided(p, HumanReviewDecision.APPROVE);

        verify(approvalService).resolveApprovalForOrg(eq("appr-5"), eq("APPROVED"),
                eq("human-review-bridge"), eq("org-1"));
    }

    @Test
    void onDecided_approvalServiceThrows_swallowsAndLogs() {
        // Migration-window expected case: pending row exists but no legacy Approval
        // exists yet (HitlAdvisor dual-tracking lands in REQ-HR-4.5).
        when(applicationContext.getBean(ApprovalService.class)).thenReturn(approvalService);
        org.mockito.Mockito.doThrow(new RuntimeException("Approval not found"))
                .when(approvalService).resolveApprovalForOrg(eq("appr-6"), eq("APPROVED"), eq("alice"), eq("org-1"));

        HumanReviewPending p = pending("appr-6", "org-1", "alice");

        // Must not throw — HumanReviewService isolates handler failures from row settlement.
        assertDoesNotThrow(() -> handler.onDecided(p, HumanReviewDecision.APPROVE));
    }

    private static HumanReviewPending pending(String id, String orgId, String decidedBy) {
        HumanReviewPending p = new HumanReviewPending();
        p.setId(id);
        p.setOrgId(orgId);
        p.setSubjectType("AGENT_TOOL_CALL");
        p.setSubjectId("tool-call-stub");
        p.setRunId("agentrun-stub");
        p.setDecidedBy(decidedBy);
        return p;
    }
}
