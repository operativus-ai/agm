package ai.operativus.agentmanager.integration.teams;

import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.control.repository.HumanReviewPendingRepository;
import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.control.service.HumanReviewService;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.model.HumanReview;
import ai.operativus.agentmanager.core.model.RequiredActionType;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import ai.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: REQ-HR follow-up — end-to-end pin for the
 *     TEAM_MEMBER_DISPATCH dispatcher wiring's PAUSE PHASE on the SEQUENTIAL
 *     strategy. Validates that PR-1..PR-3a glue together against a real
 *     Spring + Postgres context:
 *
 *     <ol>
 *       <li>A Sequential team whose first member has {@code humanReview}
 *           with {@code requiresConfirmation=true} pauses BEFORE dispatching
 *           that member.</li>
 *       <li>{@code TeamMemberHumanReviewGate} writes a
 *           {@code human_review_pending} row with
 *           {@code subject_type=TEAM_MEMBER_DISPATCH}, cursor data in the
 *           options map, and throws {@link ai.operativus.agentmanager.core.exception.TeamMemberPausedException}
 *           with a {@code TEAM_MEMBER_DISPATCH_APPROVAL} required-action.</li>
 *       <li>{@code AgentService.run}'s team branch catches it and finalizes
 *           the team run as PAUSED with the lifted required-action.</li>
 *     </ol>
 *
 *     <p><strong>Resume phase</strong> — covered by the three additional
 *     tests below (APPROVE / REJECT+SKIP / REJECT+CANCEL). The team→member
 *     session-id reuse path is enabled by the PR-4.6
 *     {@code AgentService.ensureSessionExists} relaxation: the agent_id
 *     mismatch guard is skipped when {@code AgentContextHolder.getOrchestrationDepth()
 *     > 0} (i.e. dispatched from inside a team orchestrator). The cross-tenant
 *     orgId guard is still enforced.
 *
 *     <p><strong>Tagged @integration</strong> — excluded from {@code ./mvnw test}
 *     by default. Run with
 *     {@code ./mvnw test -Dexcluded.groups= -Dtest=SequentialTeamMemberDispatchE2eRuntimeTest}.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class})
public class SequentialTeamMemberDispatchE2eRuntimeTest extends BaseIntegrationTest {

    private static final String ORG = TenantConstants.DEFAULT_SYSTEM_ORG;

    @Autowired private AgentOperations agentOperations;
    @Autowired private AgentRepository agentRepository;
    @Autowired private HumanReviewService humanReviewService;
    @Autowired private HumanReviewPendingRepository pendingRepository;
    @Autowired private RunRepository runRepository;

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    @DisplayName("PAUSE phase — Sequential team with first member gated → team run paused with TEAM_MEMBER_DISPATCH_APPROVAL")
    void pausePhase_firstMemberGated_finalizesPausedWithApprovalRequiredAction() {
        // First-member-gated avoids the team→member session-id reuse path that
        // currently hits ensureSessionExists's agent_id mismatch guard (see
        // class javadoc). No inner runner.run is invoked because the gate
        // throws before dispatch — making this PAUSE phase cleanly E2E-able.
        String gated = persistAgent("seq-e2e-gated",
                new HumanReview(true, null, null,
                        OnRejectPolicy.SKIP, null, null, null, null, null));
        String tail = persistAgent("seq-e2e-tail", null);
        String teamId = persistSequentialTeam("seq-e2e-team", List.of(gated, tail));
        seedSession("sess-e2e-pause");

        RunResponse pausedResp = agentOperations.run(teamId, "hello", null,
                "sess-e2e-pause", "user-e2e", ORG, false, null);

        assertNotNull(pausedResp);
        assertEquals(RunStatus.PAUSED, pausedResp.status(),
                "team run should pause at the gated member's pre-dispatch gate");

        AgentRun teamRun = findRun(pausedResp.runId());
        assertEquals(RunStatus.PAUSED, teamRun.getStatus());
        assertNotNull(teamRun.getRequiredAction());
        assertTrue(teamRun.getRequiredAction().contains(RequiredActionType.TEAM_MEMBER_DISPATCH_APPROVAL.name()),
                "team run requiredAction should carry TEAM_MEMBER_DISPATCH_APPROVAL, got: "
                        + teamRun.getRequiredAction());

        HumanReviewPending pending = awaitPendingForRun(pausedResp.runId());
        assertEquals("TEAM_MEMBER_DISPATCH", pending.getSubjectType());
        assertEquals(gated, pending.getSubjectId(),
                "subject_id should be the gated memberAgentId (mirrors AGENT_TOOL_CALL.toolName encoding)");
        assertNotNull(pending.getOptions());
        assertEquals("SEQUENTIAL", pending.getOptions().get("strategy"));
        assertEquals(teamId, pending.getOptions().get("teamId"));
        assertEquals(gated, pending.getOptions().get("memberAgentId"));
        assertEquals(0, ((Number) pending.getOptions().get("memberIndex")).intValue(),
                "first member → memberIndex=0");
    }

    @Test
    @DisplayName("APPROVE path — decide(APPROVE) resumes through the gated member; team run COMPLETED")
    void approvePath_resumesAndCompletes() {
        String gated = persistAgent("seq-e2e-approve-gated",
                new HumanReview(true, null, null,
                        OnRejectPolicy.SKIP, null, null, null, null, null));
        String tail = persistAgent("seq-e2e-approve-tail", null);
        String teamId = persistSequentialTeam("seq-e2e-approve-team", List.of(gated, tail));
        seedSession("sess-e2e-approve");

        RunResponse pausedResp = agentOperations.run(teamId, "go", null,
                "sess-e2e-approve", "user-e2e", ORG, false, null);
        assertEquals(RunStatus.PAUSED, pausedResp.status());
        HumanReviewPending pending = awaitPendingForRun(pausedResp.runId());

        humanReviewService.decide(pending.getId(), ORG, HumanReviewDecision.APPROVE,
                null, "test-operator");

        Awaitility.await("team run COMPLETED after decide(APPROVE)")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> findRun(pausedResp.runId()).getStatus() == RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("REJECT + on_reject=SKIP — decide(REJECT) skips gated member; team COMPLETED")
    void rejectSkipPath_skipsGatedMember_completes() {
        String gated = persistAgent("seq-e2e-skip-gated",
                new HumanReview(true, null, null,
                        OnRejectPolicy.SKIP, null, null, null, null, null));
        String tail = persistAgent("seq-e2e-skip-tail", null);
        String teamId = persistSequentialTeam("seq-e2e-skip-team", List.of(gated, tail));
        seedSession("sess-e2e-skip");

        RunResponse pausedResp = agentOperations.run(teamId, "go", null,
                "sess-e2e-skip", "user-e2e", ORG, false, null);
        assertEquals(RunStatus.PAUSED, pausedResp.status());
        HumanReviewPending pending = awaitPendingForRun(pausedResp.runId());

        humanReviewService.decide(pending.getId(), ORG, HumanReviewDecision.REJECT,
                null, "test-operator");

        Awaitility.await("team run COMPLETED after decide(REJECT + SKIP policy)")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> findRun(pausedResp.runId()).getStatus() == RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("REJECT + on_reject=CANCEL — decide(REJECT) cancels the team run")
    void rejectCancelPath_cancelsTeamRun() {
        String gated = persistAgent("seq-e2e-cancel-gated",
                new HumanReview(true, null, null,
                        OnRejectPolicy.CANCEL, null, null, null, null, null));
        String teamId = persistSequentialTeam("seq-e2e-cancel-team", List.of(gated));
        seedSession("sess-e2e-cancel");

        RunResponse pausedResp = agentOperations.run(teamId, "go", null,
                "sess-e2e-cancel", "user-e2e", ORG, false, null);
        assertEquals(RunStatus.PAUSED, pausedResp.status());
        HumanReviewPending pending = awaitPendingForRun(pausedResp.runId());

        humanReviewService.decide(pending.getId(), ORG, HumanReviewDecision.REJECT,
                null, "test-operator");

        Awaitility.await("team run CANCELLED after decide(REJECT + CANCEL policy)")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> findRun(pausedResp.runId()).getStatus() == RunStatus.CANCELLED);
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    private String persistAgent(String id, HumanReview humanReview) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(ORG);
        a.setName(id);
        a.setDescription("e2e member: " + id);
        a.setInstructions("e2e member instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        if (humanReview != null) {
            a.setHumanReview(humanReview);
        }
        return agentRepository.save(a).getId();
    }

    private String persistSequentialTeam(String id, List<String> memberIds) {
        AgentEntity team = new AgentEntity();
        team.setId(id);
        team.setOrgId(ORG);
        team.setName(id);
        team.setDescription("e2e sequential team: " + id);
        team.setInstructions("e2e team instructions");
        team.setModelId("gpt-4o-mini");
        team.setActive(true);
        team.setMaintenanceMode(false);
        team.setTeam(true);
        team.setTeamMode("SEQUENTIAL");
        team.setMembers(memberIds);
        return agentRepository.save(team).getId();
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'user-e2e', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private AgentRun findRun(String runId) {
        Optional<AgentRun> maybe = ((org.springframework.data.repository.CrudRepository<AgentRun, String>) runRepository)
                .findById(runId);
        return maybe.orElseThrow(() -> new AssertionError("no AgentRun with id " + runId));
    }

    private HumanReviewPending awaitPendingForRun(String runId) {
        Awaitility.await("human_review_pending row for run " + runId)
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> !pendingRepository.findByRunIdOrderByCreatedAtDesc(runId).isEmpty());
        return pendingRepository.findByRunIdOrderByCreatedAtDesc(runId).stream()
                .filter(p -> p.getDecision() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no undecided pending row for run " + runId));
    }
}
