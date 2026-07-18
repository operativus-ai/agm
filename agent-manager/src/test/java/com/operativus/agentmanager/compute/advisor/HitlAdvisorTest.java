package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.service.HumanReviewService;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.exception.ApprovalRequiredException;
import com.operativus.agentmanager.core.model.ApprovalDTO;
import com.operativus.agentmanager.core.model.DecisionPackage;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import com.operativus.agentmanager.core.model.enums.OnErrorPolicy;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import com.operativus.agentmanager.core.model.enums.OnTimeoutPolicy;
import com.operativus.agentmanager.core.registry.ApprovalOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class HitlAdvisorTest {

    @Mock
    private ApprovalOperations approvalService;

    @Mock
    private AgentRepository agentRepo;

    @Mock
    private HumanReviewService humanReviewService;

    private HitlAdvisor hitlAdvisor;

    @Mock
    private ChatClientRequest request;

    @Mock
    private CallAdvisorChain callChain;

    @Mock
    private StreamAdvisorChain streamChain;

    @Mock
    private ChatClientResponse response;

    @BeforeEach
    void setUp() {
        hitlAdvisor = new HitlAdvisor(approvalService, agentRepo, humanReviewService,
                new SimpleMeterRegistry(), java.util.List.of(), false, java.util.Set.of());
    }

    @Test
    void adviseCall_PassesThrough() {
        when(callChain.nextCall(request)).thenReturn(response);
        ChatClientResponse result = hitlAdvisor.adviseCall(request, callChain);
        assertSame(response, result);
        verify(callChain, times(1)).nextCall(request);
    }

    @Test
    void adviseStream_PassesThrough() {
        Flux<ChatClientResponse> fluxResponse = Flux.just(response);
        when(streamChain.nextStream(request)).thenReturn(fluxResponse);
        Flux<ChatClientResponse> result = hitlAdvisor.adviseStream(request, streamChain);
        assertSame(fluxResponse, result);
        verify(streamChain, times(1)).nextStream(request);
    }

    @Test
    void requiresHitl_DetectsCorrectTools() {
        // Destructive
        assertTrue(hitlAdvisor.requiresHitl("delete_database"));
        assertTrue(hitlAdvisor.requiresHitl("truncate_tables"));
        assertTrue(hitlAdvisor.requiresHitl("SensitiveOperationsTool"));
        // Code-execution tools — both sandbox paths gate identically (run_python is the
        // in-container interpreter; e2b_execute_python the remote sandbox).
        assertTrue(hitlAdvisor.requiresHitl("e2b_execute_python"));
        assertTrue(hitlAdvisor.requiresHitl("run_python"));

        // FinOps gate is OFF by default — the seeded tool is NOT gated unless an operator enables it
        assertFalse(hitlAdvisor.requiresHitl("bulkIngestDocumentationSite"));

        // Safe
        assertFalse(hitlAdvisor.requiresHitl("get_weather"));
        assertFalse(hitlAdvisor.requiresHitl("search_database"));
    }

    @Test
    void requiresHitl_finopsGateEnabled_gatesConfiguredToolOnly() {
        HitlAdvisor finopsOn = new HitlAdvisor(approvalService, agentRepo, humanReviewService,
                new SimpleMeterRegistry(), java.util.List.of(),
                true, java.util.Set.of("bulkIngestDocumentationSite"));
        assertTrue(finopsOn.requiresHitl("bulkIngestDocumentationSite"),
                "enabled FinOps gate must require HITL for a configured tool");
        assertFalse(finopsOn.requiresHitl("readWebpage"),
                "FinOps gate must not gate tools outside the configured list");
        // Destructive tools still gate regardless of the FinOps gate
        assertTrue(finopsOn.requiresHitl("run_python"));
    }

    @Test
    void requireApprovalForTool_NullRunId_FailsLoud() {
        // Per audit F1 design pass: missing context must fail loud rather than poison
        // approval-history audit data with UNKNOWN_RUN/UNKNOWN_SESSION/UNKNOWN_AGENT placeholders.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                hitlAdvisor.requireApprovalForTool("delete_database", "{}", null, "sess", "agent"));
        assertTrue(ex.getMessage().contains("HITL invoked without bound run context"));
        verify(approvalService, never()).createApprovalRequest(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void requireApprovalForTool_DestructiveTool_ThrowsExceptionWithTier3() {
        String toolName = "drop_schema";
        String runId = "run-1";
        String sessionId = "sess-1";
        String agentId = "agent-1";
        String args = "{\"schema\":\"public\"}";

        ApprovalDTO mockApproval = mock(ApprovalDTO.class);
        lenient().when(mockApproval.id()).thenReturn("approval-id");

        when(approvalService.createApprovalRequest(
                eq(runId), eq(sessionId), eq(agentId), eq(toolName), eq(args),
                anyString(), eq("SYSTEM"), anyString(), anyString(),
                eq(DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE)
        )).thenReturn(mockApproval);

        ApprovalRequiredException ex = assertThrows(ApprovalRequiredException.class, () ->
                hitlAdvisor.requireApprovalForTool(toolName, args, runId, sessionId, agentId)
        );

        assertEquals("approval-id", ex.getApprovalId());
        assertEquals(toolName, ex.getToolName());
    }

    @Test
    void requireApprovalForTool_FinOpsTool_ThrowsExceptionWithTier2() {
        // FinOps routing only fires when the gate is enabled and the tool is configured.
        HitlAdvisor finopsOn = new HitlAdvisor(approvalService, agentRepo, humanReviewService,
                new SimpleMeterRegistry(), java.util.List.of(),
                true, java.util.Set.of("bulkIngestDocumentationSite"));
        String toolName = "bulkIngestDocumentationSite";

        ApprovalDTO mockApproval = mock(ApprovalDTO.class);
        lenient().when(mockApproval.id()).thenReturn("approval-id-2");

        when(approvalService.createApprovalRequest(
                anyString(), anyString(), anyString(), eq(toolName), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                eq(DecisionPackage.DecisionTier.TIER_2_FINOPS_BLOCK)
        )).thenReturn(mockApproval);

        assertThrows(ApprovalRequiredException.class, () ->
                finopsOn.requireApprovalForTool(toolName, "{}", "run-2", "sess-2", "agent-2")
        );
    }

    @Test
    void requireApprovalForTool_AgentHasPauseActiveHumanReview_WritesHumanReviewPendingWithSameId() {
        // REQ-HR-4.5 dual-tracking: when the agent has a HumanReview config with an
        // active pause gate, HitlAdvisor must also write a HumanReviewPending row
        // using the SAME id as the legacy approval, so AgentToolResumeHandler can
        // bridge resume by that shared id.
        String toolName = "delete_database";
        String runId = "run-hr";
        String sessionId = "sess-hr";
        String agentId = "agent-hr";
        String orgId = "org-hr";
        String pendingId = "shared-id-1";

        ApprovalDTO mockApproval = mock(ApprovalDTO.class);
        when(mockApproval.id()).thenReturn(pendingId);
        when(approvalService.createApprovalRequest(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(mockApproval);

        AgentEntity agent = mock(AgentEntity.class);
        HumanReview hr = new HumanReview(true, null, null,
                OnRejectPolicy.SKIP, OnTimeoutPolicy.AUTO_REJECT, OnErrorPolicy.CANCEL,
                30L, null, null);
        when(agent.getHumanReview()).thenReturn(hr);
        when(agent.getOrgId()).thenReturn(orgId);
        when(agentRepo.findById(agentId)).thenReturn(Optional.of(agent));

        assertThrows(ApprovalRequiredException.class, () ->
                hitlAdvisor.requireApprovalForTool(toolName, "{}", runId, sessionId, agentId));

        verify(humanReviewService).pauseForWithId(
                eq(pendingId),
                eq(HumanReviewSubjectType.AGENT_TOOL_CALL),
                eq(toolName),
                eq(runId),
                eq(orgId),
                anyString(),
                eq(hr),
                eq(null),
                anyString());
    }

    @Test
    void requireApprovalForTool_AgentHasNoHumanReview_SkipsDualTrack() {
        // When the agent has no HumanReview config, the dual-tracking path must be
        // skipped — only the legacy ApprovalOperations.createApprovalRequest fires.
        AgentEntity agent = mock(AgentEntity.class);
        when(agent.getHumanReview()).thenReturn(null);
        when(agentRepo.findById("agent-x")).thenReturn(Optional.of(agent));

        ApprovalDTO mockApproval = mock(ApprovalDTO.class);
        lenient().when(mockApproval.id()).thenReturn("approval-x");
        when(approvalService.createApprovalRequest(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(mockApproval);

        assertThrows(ApprovalRequiredException.class, () ->
                hitlAdvisor.requireApprovalForTool("delete_database", "{}", "run-x", "sess-x", "agent-x"));

        verifyNoInteractions(humanReviewService);
    }

    @Test
    void requireApprovalForTool_HumanReviewPauseInactive_SkipsDualTrack() {
        // Config exists but no requires* flag is true → isPauseActive() returns false
        // → dual-tracking path must be skipped.
        AgentEntity agent = mock(AgentEntity.class);
        HumanReview hr = new HumanReview(false, false, false,
                OnRejectPolicy.CANCEL, OnTimeoutPolicy.AUTO_REJECT, OnErrorPolicy.CANCEL,
                null, null, null);
        when(agent.getHumanReview()).thenReturn(hr);
        when(agentRepo.findById("agent-y")).thenReturn(Optional.of(agent));

        ApprovalDTO mockApproval = mock(ApprovalDTO.class);
        lenient().when(mockApproval.id()).thenReturn("approval-y");
        when(approvalService.createApprovalRequest(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(mockApproval);

        assertThrows(ApprovalRequiredException.class, () ->
                hitlAdvisor.requireApprovalForTool("delete_database", "{}", "run-y", "sess-y", "agent-y"));

        verifyNoInteractions(humanReviewService);
    }

    @Test
    void requireApprovalForTool_DualTrackFailure_DoesNotBreakLegacyPath() {
        // Legacy approval is primary; a HumanReviewPending write failure must be
        // swallowed + logged so the ApprovalRequiredException still propagates.
        AgentEntity agent = mock(AgentEntity.class);
        HumanReview hr = new HumanReview(true, null, null,
                OnRejectPolicy.SKIP, OnTimeoutPolicy.AUTO_REJECT, OnErrorPolicy.CANCEL,
                null, null, null);
        when(agent.getHumanReview()).thenReturn(hr);
        when(agent.getOrgId()).thenReturn("org-z");
        when(agentRepo.findById("agent-z")).thenReturn(Optional.of(agent));

        ApprovalDTO mockApproval = mock(ApprovalDTO.class);
        when(mockApproval.id()).thenReturn("approval-z");
        when(approvalService.createApprovalRequest(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(mockApproval);

        when(humanReviewService.pauseForWithId(
                anyString(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        // Legacy ApprovalRequiredException MUST still propagate.
        assertThrows(ApprovalRequiredException.class, () ->
                hitlAdvisor.requireApprovalForTool("delete_database", "{}", "run-z", "sess-z", "agent-z"));
    }

    @Test
    void requireApprovalForTool_DefaultTool_ThrowsExceptionWithTier1() {
        String toolName = "unknown_risky_tool";
        
        ApprovalDTO mockApproval = mock(ApprovalDTO.class);
        lenient().when(mockApproval.id()).thenReturn("approval-id-3");

        when(approvalService.createApprovalRequest(
                anyString(), anyString(), anyString(), eq(toolName), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                eq(DecisionPackage.DecisionTier.TIER_1_SAFE)
        )).thenReturn(mockApproval);

        assertThrows(ApprovalRequiredException.class, () ->
                hitlAdvisor.requireApprovalForTool(toolName, "{}", "run-3", "sess-3", "agent-3")
        );
    }
}
