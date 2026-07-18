package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.core.exception.TeamMemberPausedException;
import ai.operativus.agentmanager.core.model.HumanReview;
import ai.operativus.agentmanager.core.model.RequiredAction;
import ai.operativus.agentmanager.core.model.RequiredActionType;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit pins for {@link SequentialOrchestrator}'s integration with
 * {@link TeamMemberHumanReviewGate}. Verifies the gate is called per member
 * with the right cursor data and that a gate throw unwinds the loop.
 */
class SequentialOrchestratorGateTest {

    private AgentRegistry agentRegistry;
    private TransitionValidator transitionValidator;
    private TierEscalationValidator tierEscalationValidator;
    private OrchestratorMembers orchestratorMembers;
    private TeamMemberHumanReviewGate humanReviewGate;
    private SequentialOrchestrator orchestrator;
    private AgentOperations runner;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        transitionValidator = mock(TransitionValidator.class);
        tierEscalationValidator = mock(TierEscalationValidator.class);
        orchestratorMembers = mock(OrchestratorMembers.class);
        humanReviewGate = mock(TeamMemberHumanReviewGate.class);
        runner = mock(AgentOperations.class);
        orchestrator = new SequentialOrchestrator(agentRegistry, transitionValidator,
                tierEscalationValidator, orchestratorMembers, humanReviewGate);
    }

    @Test
    @DisplayName("gate is called for each member in declared order with the right cursor data")
    void gateCalledPerMember_inOrder_withCursorData() {
        AgentDefinition team = team();
        AgentDefinition memberA = member("member-a");
        AgentDefinition memberB = member("member-b");
        when(orchestratorMembers.resolveActive(eq(team), eq(agentRegistry), eq("org-1"), eq("user-1"), any()))
                .thenReturn(List.of(memberA, memberB));
        when(runner.run(eq("member-a"), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new RunResponse("run-a", "sess-1", "out-A", null, null, null, RunStatus.COMPLETED, null));
        when(runner.run(eq("member-b"), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new RunResponse("run-b", "sess-1", "out-B", null, null, null, RunStatus.COMPLETED, null));

        String result = orchestrator.execute(team, "init", null, "sess-1", "user-1",
                "org-1", false, runner);

        assertThat(result).isEqualTo("out-B");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cursorCaptor = ArgumentCaptor.forClass(Map.class);
        verify(humanReviewGate).requireApprovalIfConfigured(
                eq(memberA), any(), eq("team-1"), eq("member-a"), eq("org-1"),
                cursorCaptor.capture());
        Map<String, Object> firstCursor = cursorCaptor.getValue();
        assertThat(firstCursor).containsEntry(TeamMemberDispatchExtraOptions.STRATEGY, "SEQUENTIAL");
        assertThat(firstCursor).containsEntry(TeamMemberDispatchExtraOptions.CURRENT_INPUT, "init");
        assertThat(firstCursor).containsEntry(TeamMemberDispatchExtraOptions.SESSION_ID, "sess-1");
        assertThat(firstCursor).containsEntry(TeamMemberDispatchExtraOptions.USER_ID, "user-1");
        assertThat(firstCursor).containsEntry(TeamMemberDispatchExtraOptions.MEMBER_INDEX, 0);

        // Second call carries the prior member's output as currentInput
        verify(humanReviewGate).requireApprovalIfConfigured(
                eq(memberB), any(), eq("team-1"), eq("member-b"), eq("org-1"),
                cursorCaptor.capture());
        Map<String, Object> secondCursor = cursorCaptor.getValue();
        assertThat(secondCursor).containsEntry(TeamMemberDispatchExtraOptions.CURRENT_INPUT, "out-A");
        assertThat(secondCursor).containsEntry(TeamMemberDispatchExtraOptions.MEMBER_INDEX, 1);
    }

    @Test
    @DisplayName("gate throw at member index 1 unwinds loop; member-2 is never dispatched")
    void gateThrowAtMember2_unwindsLoop() {
        AgentDefinition team = team();
        AgentDefinition memberA = member("member-a");
        AgentDefinition memberB = member("member-b");
        when(orchestratorMembers.resolveActive(eq(team), eq(agentRegistry), eq("org-1"), eq("user-1"), any()))
                .thenReturn(List.of(memberA, memberB));
        when(runner.run(eq("member-a"), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new RunResponse("run-a", "sess-1", "out-A", null, null, null, RunStatus.COMPLETED, null));

        RequiredAction ra = RequiredAction.teamMemberDispatchApproval(
                "team-1", "member-b", "hrp-1", null, null, null);
        doAnswer(inv -> { throw new TeamMemberPausedException("run-team", "member-b", ra); })
                .when(humanReviewGate).requireApprovalIfConfigured(
                        eq(memberB), any(), any(), eq("member-b"), any(), anyMap());

        assertThatThrownBy(() -> orchestrator.execute(team, "init", null, "sess-1", "user-1",
                "org-1", false, runner))
                .isInstanceOf(TeamMemberPausedException.class)
                .hasFieldOrPropertyWithValue("pausedAgentId", "member-b");

        // member-b's runner.run was never invoked (gate threw before dispatch)
        verify(runner, never()).run(eq("member-b"), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        // member-a was called normally
        verify(runner).run(eq("member-a"), any(), any(), any(), any(), any(),
                anyBoolean(), any());
    }

    private static AgentDefinition team() {
        return new AgentDefinition(
                "team-1", "T", "D", "I", "gpt-x",
                null, null, null, null,
                false, true, "SEQUENTIAL",
                List.of("member-a", "member-b"),
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ai.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                false, null, null, null);
    }

    private static AgentDefinition member(String id) {
        return new AgentDefinition(
                id, id, "desc", "instr", "gpt-x",
                null, null, null, null,
                false, false, null,
                null, null, false, false, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ai.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                false, null,
                new HumanReview(true, null, null,
                        OnRejectPolicy.SKIP, null, null, null, null, null), null);
    }
}
