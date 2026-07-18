package ai.operativus.agentmanager.integration.hitl;

import ai.operativus.agentmanager.compute.service.RunExecutionManager;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Tier 2.4 PR 7 F-B
 *   stuck-PAUSED triage classifier — pre-fix, every PAUSED run older than 24h
 *   was cancelled with the same generic reason regardless of WHY. Post-fix,
 *   the sweeper reads the corresponding approval's status and stamps the
 *   classification label into the cancellation reason + RUN_CANCELLED event payload.
 *
 *   Each test case seeds an agent_runs row with a 48h-old created_at and a
 *   matched approval row with a specific status, then invokes the autowired
 *   RunExecutionManager.expireStuckPausedRuns directly and asserts the persisted
 *   output text carries the expected classification label.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class StuckPausedTriageRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private RunExecutionManager runExecutionManager;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    @Test
    void stuckPausedWithExpiredApproval_classifiesUpstreamExpiry() {
        String orgId = "org-expired";
        String agentId = seedAgent(orgId);
        String sessionId = seedSession(orgId, agentId);
        String runId = seedStuckPausedRun(agentId, sessionId, orgId);
        seedApproval(runId, sessionId, agentId, orgId, "EXPIRED", null);

        runExecutionManager.expireStuckPausedRuns();

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
        assertEquals("CANCELLED", status, "sweep must finalize stuck PAUSED → CANCELLED");

        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, runId);
        assertNotNull(output);
        assertTrue(output.contains("approval_expired_upstream"),
                "cancellation reason must carry the F-B classification label; was: " + output);
    }

    @Test
    void stuckPausedWithPendingApproval_classifiesUserAbandoned() {
        String orgId = "org-abandoned";
        String agentId = seedAgent(orgId);
        String sessionId = seedSession(orgId, agentId);
        String runId = seedStuckPausedRun(agentId, sessionId, orgId);
        seedApproval(runId, sessionId, agentId, orgId, "PENDING", null);

        runExecutionManager.expireStuckPausedRuns();

        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, runId);
        assertTrue(output.contains("user_abandoned"),
                "PENDING approval without workflowRunId should classify as user_abandoned; was: " + output);
    }

    // ─── helpers ───

    private String seedAgent(String orgId) {
        String agentId = "agent-triage-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Triage Agent " + agentId, orgId);
        return agentId;
    }

    private String seedSession(String orgId, String agentId) {
        String sessionId = "session-triage-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, "triage-user", orgId, agentId);
        return sessionId;
    }

    private String seedStuckPausedRun(String agentId, String sessionId, String orgId) {
        String runId = "run-triage-" + UUID.randomUUID();
        LocalDateTime stale = LocalDateTime.now().minusHours(48);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status,
                                        input, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'PAUSED', 'input', ?, ?, 0)
                """, runId, agentId, sessionId, "triage-user", orgId, stale, stale);
        return runId;
    }

    private void seedApproval(String runId, String sessionId, String agentId, String orgId,
                              String status, String workflowRunId) {
        String approvalId = "approval-triage-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, workflow_run_id, session_id, agent_id, status,
                                       tool_name, tool_arguments, requested_by, decision_tier,
                                       org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::varchar, 'delete_database',
                        '{}'::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now())
                """, approvalId, runId, workflowRunId, sessionId, agentId, status,
                "triage-user", orgId);
    }
}
