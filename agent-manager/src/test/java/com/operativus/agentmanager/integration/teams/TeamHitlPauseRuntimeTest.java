package com.operativus.agentmanager.integration.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.compute.service.AgentService;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.RequiredAction;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Tier 2.5 F2/F3 team-pause
 *   continueRun rejection contract. Pre-fix, calling continueRun on a team-level paused
 *   run row would silently re-run the team's resume path against an entity that has no
 *   resumable state — corrupting the team's row. Post-fix, the AgentService.continueRun
 *   path detects team-paused rows via the lifted requiredAction.pausedChildRunId field
 *   (F2) or via def.isTeam() (F3) and rejects with a BusinessValidationException pointing
 *   the caller at the correct resolution path.
 *
 *   These tests seed the agent_runs row directly via JDBC and exercise AgentService.continueRun
 *   on the autowired bean. The AgentService.continueRun call path mirrors what
 *   ApprovalService.resolveApprovalForOrg invokes from its fire-and-forget VT — the
 *   rejection here would manifest there as a logged WARN, but the contract is the same.
 *
 *   End-to-end "team-pauses-when-member-pauses" coverage (real LLM with HITL tool wired
 *   through fake chat model) is intentionally deferred — see PR description / Tier 2.5
 *   F2-F3 spec §5b. The unit tests in AgentServiceTest already pin the lift-up contract
 *   against mock executeSync throws.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class TeamHitlPauseRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private AgentService agentService;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        // agents.model_id has a FK to models.id — seed the model row first.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    @Test
    void continueRun_teamMemberPauseRow_rejectsWithChildRunIdHint() throws Exception {
        // F2 — seed a team-level PAUSED row whose required_action carries a pausedChildRunId
        // (lifted from the child member that actually holds the approval).
        String orgId = "org-team-f2";
        String childRunId = "child-run-" + UUID.randomUUID();
        String teamRunId = "team-run-" + UUID.randomUUID();
        String teamAgentId = "team-agent-" + UUID.randomUUID();
        String sessionId = "sess-" + UUID.randomUUID();

        seedTeamAgent(teamAgentId, orgId, "SEQUENTIAL");
        seedSession(sessionId, "team-f2-user", orgId, teamAgentId);

        RequiredAction lifted = RequiredAction.teamMemberPause(
                RequiredAction.toolApproval(
                        "delete_file", "{\"path\":\"/tmp/x\"}", "approval-1",
                        "trace-1", "lineage", "depth=1"),
                childRunId);
        String requiredActionJson = JSON.writeValueAsString(lifted);

        seedAgentRun(teamRunId, teamAgentId, sessionId, "team-f2-user", orgId,
                "team paused on member", requiredActionJson);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                agentService.continueRun(teamRunId, "APPROVED"));

        assertTrue(ex.getMessage().contains(childRunId),
                "rejection message must point at the paused child runId; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("/api/v1/approvals/approval-1/resolve"),
                "rejection message must direct the caller at the typed approvals resolve endpoint "
                        + "for the child's TOOL_APPROVAL payload (post-#406 sister to /v1/escalations); was: "
                        + ex.getMessage());

        // The team's row must remain untouched (PAUSED) — the rejection must NOT trigger any
        // finalize / status-mutate side effects.
        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, teamRunId);
        assertEquals(RunStatus.PAUSED.name(), status,
                "team row must remain PAUSED after rejection — anything else means a finalize leak");
    }

    @Test
    void continueRun_teamLevelEscalationRow_rejectsWithReinvokeHint() throws Exception {
        // F3 — seed a team-level PAUSED row whose required_action carries a SWARM_ESCALATION_APPROVAL
        // (no child member ran — the strategy threw the escalation synchronously). pausedChildRunId
        // is null on this path; rejection must direct the caller to re-invoke.
        String orgId = "org-team-f3";
        String teamRunId = "team-f3-run-" + UUID.randomUUID();
        String teamAgentId = "team-f3-agent-" + UUID.randomUUID();
        String sessionId = "sess-f3-" + UUID.randomUUID();

        seedTeamAgent(teamAgentId, orgId, "ROUTER");
        seedSession(sessionId, "team-f3-user", orgId, teamAgentId);

        RequiredAction f3Payload = RequiredAction.swarmEscalation(
                "src-agent", "tgt-agent", 1, 2, "esc-1",
                "trace-f3", "lineage", "depth=0");
        String requiredActionJson = JSON.writeValueAsString(f3Payload);

        seedAgentRun(teamRunId, teamAgentId, sessionId, "team-f3-user", orgId,
                "team escalation blocked", requiredActionJson);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                agentService.continueRun(teamRunId, "APPROVED"));

        assertTrue(ex.getMessage().contains("Re-invoke the team agent from scratch"),
                "F3 rejection message must direct to re-invocation; was: " + ex.getMessage());

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, teamRunId);
        assertEquals(RunStatus.PAUSED.name(), status,
                "team row must remain PAUSED after F3 rejection");
    }

    // ─── helpers ───

    private void seedTeamAgent(String agentId, String orgId, String teamMode) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, is_team, team_mode, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, true, ?, ?, now(), now())
                """, agentId, "Team Agent " + agentId, teamMode, orgId);
    }

    private void seedSession(String sessionId, String userId, String orgId, String agentId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, userId, orgId, agentId);
    }

    private void seedAgentRun(String runId, String agentId, String sessionId, String userId,
                              String orgId, String output, String requiredActionJson) {
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status,
                                        input, output, required_action, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'PAUSED', ?, ?, ?, now(), now(), 0)
                """, runId, agentId, sessionId, userId, orgId, "team kickoff input", output, requiredActionJson);
    }
}
