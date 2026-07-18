package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.compute.service.AgentRunFinalizer;
import com.operativus.agentmanager.compute.teams.SequentialOrchestrator;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the resume contract for {@link TeamMemberDispatchResumeHandler} —
 * the real implementation (post REQ-HR follow-up PR-4) reads cursor data from
 * the pending row's options map and routes to {@link SequentialOrchestrator}
 * for {@code SEQUENTIAL} strategy resume, or to {@link AgentRunFinalizer} for
 * cancellation paths.
 */
class TeamMemberDispatchResumeHandlerTest {

    private ApplicationContext applicationContext;
    private RunRepository runRepository;
    private SequentialOrchestrator orchestrator;
    private AgentRegistry agentRegistry;
    private AgentRunFinalizer finalizer;
    private AgentOperations runner;
    private TeamMemberDispatchResumeHandler handler;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        runRepository = mock(RunRepository.class);
        orchestrator = mock(SequentialOrchestrator.class);
        agentRegistry = mock(AgentRegistry.class);
        finalizer = mock(AgentRunFinalizer.class);
        runner = mock(AgentOperations.class);
        when(applicationContext.getBean(SequentialOrchestrator.class)).thenReturn(orchestrator);
        when(applicationContext.getBean(AgentRegistry.class)).thenReturn(agentRegistry);
        when(applicationContext.getBean(AgentRunFinalizer.class)).thenReturn(finalizer);
        when(applicationContext.getBean(AgentOperations.class)).thenReturn(runner);

        handler = new TeamMemberDispatchResumeHandler(applicationContext, runRepository);
    }

    @Test
    void subjectType_isTeamMemberDispatch() {
        assertThat(handler.subjectType()).isEqualTo(HumanReviewSubjectType.TEAM_MEMBER_DISPATCH);
    }

    @Test
    @DisplayName("null options map → log + no-op")
    void nullOptions_noOp() {
        HumanReviewPending p = pending(null);
        assertThatCode(() -> handler.onDecided(p, HumanReviewDecision.APPROVE))
                .doesNotThrowAnyException();
        verify(orchestrator, never()).resumeAt(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("missing cursor keys → log + no-op")
    void missingCursorKeys_noOp() {
        Map<String, Object> opts = new HashMap<>();
        opts.put(TeamMemberDispatchExtraOptions.STRATEGY, "SEQUENTIAL");
        // missing MEMBER_AGENT_ID and MEMBER_INDEX
        HumanReviewPending p = pending(opts);
        handler.onDecided(p, HumanReviewDecision.APPROVE);
        verify(orchestrator, never()).resumeAt(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CANCELLED decision → finalizer marks team run CANCELLED, no resume")
    void cancelledDecision_finalizes() {
        HumanReviewPending p = pending(cursorOpts(2));
        handler.onDecided(p, HumanReviewDecision.CANCELLED);
        verify(finalizer).finalizeRun(eq("run-team"), eq(RunStatus.CANCELLED), any(), any(), any());
        verify(orchestrator, never()).resumeAt(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("REJECT with on_reject=CANCEL → finalizer marks team run CANCELLED")
    void rejectCancel_finalizes() {
        Map<String, Object> opts = cursorOpts(2);
        opts.put("onReject", OnRejectPolicy.CANCEL.name());
        HumanReviewPending p = pending(opts);
        handler.onDecided(p, HumanReviewDecision.REJECT);
        verify(finalizer).finalizeRun(eq("run-team"), eq(RunStatus.CANCELLED), any(), any(), any());
    }

    @Test
    @DisplayName("REJECT with on_reject=SKIP → orchestrator.resumeAt called at memberIndex+1 (empty approved set)")
    void rejectSkip_resumesAtNextIndex() {
        Map<String, Object> opts = cursorOpts(2);
        opts.put("onReject", OnRejectPolicy.SKIP.name());
        HumanReviewPending p = pending(opts);
        primeRunAndTeam();
        when(orchestrator.resumeAt(any(), eq(3), any(), any(), any(), any(), any(), any()))
                .thenReturn("done");

        handler.onDecided(p, HumanReviewDecision.REJECT);

        verify(orchestrator).resumeAt(any(), eq(3), eq("prior-output"), any(),
                eq("sess-1"), eq("user-1"), eq("org-1"), eq(runner));
        verify(finalizer).finalizeRun(eq("run-team"), eq(RunStatus.COMPLETED), eq("done"), any(), any());
    }

    @Test
    @DisplayName("APPROVE → orchestrator.resumeAt called at memberIndex (gate seeded with approved member)")
    void approve_resumesAtSameIndex() {
        HumanReviewPending p = pending(cursorOpts(2));
        primeRunAndTeam();
        when(orchestrator.resumeAt(any(), eq(2), any(), any(), any(), any(), any(), any()))
                .thenReturn("approved-result");

        handler.onDecided(p, HumanReviewDecision.APPROVE);

        verify(orchestrator).resumeAt(any(), eq(2), eq("prior-output"), any(),
                eq("sess-1"), eq("user-1"), eq("org-1"), eq(runner));
        verify(finalizer).finalizeRun(eq("run-team"), eq(RunStatus.COMPLETED), eq("approved-result"),
                any(), any());
    }

    @Test
    @DisplayName("team run not found → log + no-op (no finalizer call)")
    void teamRunNotFound_noOp() {
        HumanReviewPending p = pending(cursorOpts(2));
        when(((org.springframework.data.repository.CrudRepository<AgentRun, String>) runRepository)
                .findById("run-team")).thenReturn(Optional.empty());

        handler.onDecided(p, HumanReviewDecision.APPROVE);

        verify(orchestrator, never()).resumeAt(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any());
        verify(finalizer, never()).finalizeRun(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("non-SEQUENTIAL strategy → log + no-op (pending settled, team stays paused)")
    void nonSequentialStrategy_noOp() {
        Map<String, Object> opts = cursorOpts(2);
        opts.put(TeamMemberDispatchExtraOptions.STRATEGY, "ROUTER");
        HumanReviewPending p = pending(opts);

        handler.onDecided(p, HumanReviewDecision.APPROVE);

        verify(orchestrator, never()).resumeAt(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any());
        verify(finalizer, never()).finalizeRun(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("orchestrator throws → finalizer marks team run FAILED")
    void resumeFails_finalizesAsFailed() {
        HumanReviewPending p = pending(cursorOpts(2));
        primeRunAndTeam();
        when(orchestrator.resumeAt(any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("kaboom"));

        handler.onDecided(p, HumanReviewDecision.APPROVE);

        verify(finalizer).finalizeRun(eq("run-team"), eq(RunStatus.FAILED), any(), any(), any());
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    private void primeRunAndTeam() {
        AgentRun run = new AgentRun();
        run.setId("run-team");
        run.setAgentId("team-1");
        run.setOrgId("org-1");
        when(((org.springframework.data.repository.CrudRepository<AgentRun, String>) runRepository)
                .findById("run-team")).thenReturn(Optional.of(run));
        AgentDefinition team = mock(AgentDefinition.class);
        when(team.id()).thenReturn("team-1");
        when(agentRegistry.findById("team-1", "org-1")).thenReturn(team);
    }

    private static Map<String, Object> cursorOpts(int memberIndex) {
        Map<String, Object> opts = new HashMap<>();
        opts.put(TeamMemberDispatchExtraOptions.TEAM_ID, "team-1");
        opts.put(TeamMemberDispatchExtraOptions.MEMBER_AGENT_ID, "member-a");
        opts.put(TeamMemberDispatchExtraOptions.MEMBER_INDEX, memberIndex);
        opts.put(TeamMemberDispatchExtraOptions.STRATEGY, "SEQUENTIAL");
        opts.put(TeamMemberDispatchExtraOptions.CURRENT_INPUT, "prior-output");
        opts.put(TeamMemberDispatchExtraOptions.SESSION_ID, "sess-1");
        opts.put(TeamMemberDispatchExtraOptions.USER_ID, "user-1");
        return opts;
    }

    private static HumanReviewPending pending(Map<String, Object> opts) {
        HumanReviewPending p = new HumanReviewPending();
        p.setId("hrp-1");
        p.setRunId("run-team");
        p.setSubjectType(HumanReviewSubjectType.TEAM_MEMBER_DISPATCH.name());
        p.setSubjectId("member-a");
        p.setOrgId("org-1");
        p.setOptions(opts);
        return p;
    }
}
