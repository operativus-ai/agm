package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.control.service.HumanReviewService;
import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.exception.TeamMemberPausedException;
import ai.operativus.agentmanager.core.model.HumanReview;
import ai.operativus.agentmanager.core.model.RequiredAction;
import ai.operativus.agentmanager.core.model.RequiredActionType;
import ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import ai.operativus.agentmanager.core.model.enums.OnErrorPolicy;
import ai.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import ai.operativus.agentmanager.core.model.enums.OnTimeoutPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamMemberHumanReviewGateTest {

    private HumanReviewService humanReviewService;
    private TeamMemberHumanReviewGate gate;

    @BeforeEach
    void setUp() {
        humanReviewService = mock(HumanReviewService.class);
        gate = new TeamMemberHumanReviewGate(humanReviewService);
    }

    @Test
    @DisplayName("null memberDefinition is a no-op")
    void nullDefinition_returnsSilently() {
        gate.requireApprovalIfConfigured(null, "run-1", "team-1", "member-a", "org-1");
        verify(humanReviewService, never())
                .pauseFor(any(), any(), any(), any(), any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("null humanReview is a no-op")
    void nullHumanReview_returnsSilently() {
        AgentDefinition def = definition(null);
        gate.requireApprovalIfConfigured(def, "run-1", "team-1", "member-a", "org-1");
        verify(humanReviewService, never())
                .pauseFor(any(), any(), any(), any(), any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("humanReview with no active pause flag is a no-op (policy-only config)")
    void policyOnly_returnsSilently() {
        HumanReview policyOnly = new HumanReview(
                null, null, null,
                OnRejectPolicy.CANCEL, OnTimeoutPolicy.AUTO_REJECT, OnErrorPolicy.CANCEL,
                null, null, null);
        AgentDefinition def = definition(policyOnly);
        gate.requireApprovalIfConfigured(def, "run-1", "team-1", "member-a", "org-1");
        verify(humanReviewService, never())
                .pauseFor(any(), any(), any(), any(), any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("requiresConfirmation pauses + throws TeamMemberPausedException with TEAM_MEMBER_DISPATCH_APPROVAL")
    void requiresConfirmation_pausesAndThrows() {
        HumanReview review = new HumanReview(
                true, null, null,
                OnRejectPolicy.SKIP, OnTimeoutPolicy.AUTO_REJECT, OnErrorPolicy.CANCEL,
                30L, null, null);
        AgentDefinition def = definition(review);

        HumanReviewPending pending = new HumanReviewPending();
        pending.setId("hrp-team-xyz");
        when(humanReviewService.pauseFor(
                eq(HumanReviewSubjectType.TEAM_MEMBER_DISPATCH),
                eq("member-a"),
                eq("run-1"),
                eq("org-1"),
                any(),
                eq(review),
                anyMap(),
                any()))
            .thenReturn(pending);

        TeamMemberPausedException thrown = (TeamMemberPausedException)
                assertThatThrownBy(() ->
                        gate.requireApprovalIfConfigured(def, "run-1", "team-1", "member-a", "org-1"))
                .isInstanceOf(TeamMemberPausedException.class)
                .actual();

        assertThat(thrown.getPausedRunId()).isEqualTo("run-1");
        assertThat(thrown.getPausedAgentId()).isEqualTo("member-a");
        RequiredAction ra = thrown.getRequiredAction();
        assertThat(ra).isNotNull();
        assertThat(ra.type()).isEqualTo(RequiredActionType.TEAM_MEMBER_DISPATCH_APPROVAL);
        assertThat(ra.approvalId()).isEqualTo("hrp-team-xyz");
        assertThat(ra.sourceAgentId()).isEqualTo("member-a"); // memberAgentId encoded here
        assertThat(ra.tool()).isEqualTo("team-1"); // teamId overloaded onto tool slot
    }

    @Test
    @DisplayName("extraOptions carry teamId + memberAgentId per the encoding convention")
    void extraOptionsCarryConventionKeys() {
        HumanReview review = new HumanReview(
                true, null, null,
                OnRejectPolicy.SKIP, null, null, null, null, null);
        AgentDefinition def = definition(review);

        HumanReviewPending pending = new HumanReviewPending();
        pending.setId("hrp-x");
        when(humanReviewService.pauseFor(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(pending);

        try {
            gate.requireApprovalIfConfigured(def, "run-1", "team-1", "member-a", "org-1");
        } catch (TeamMemberPausedException expected) {
            // ignored — assertions below
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> optsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(humanReviewService).pauseFor(any(), any(), any(), any(), any(), any(),
                optsCaptor.capture(), any());
        Map<String, Object> opts = optsCaptor.getValue();
        assertThat(opts).containsEntry(TeamMemberDispatchExtraOptions.TEAM_ID, "team-1");
        assertThat(opts).containsEntry(TeamMemberDispatchExtraOptions.MEMBER_AGENT_ID, "member-a");
    }

    private static AgentDefinition definition(HumanReview review) {
        return new AgentDefinition(
                "member-a", "Member A", "desc", "instr", "gpt-x",
                null, null, null, null, false, false, null,
                null, null, false, false, false, true, null,
                null, null, null, null, null, null, null,
                false, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null,
                false, null, review
        , null);
    }
}
