package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.control.repository.WorkflowRunRepository;
import ai.operativus.agentmanager.control.service.HumanReviewService;
import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import ai.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.model.workflow.DagFrontier;
import ai.operativus.agentmanager.core.model.workflow.PauseKind;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Closes the DAG-3c deferred scenario "AGENT-HITL resume via the full
 *   HumanReview service cascade". Proves the production settle path for a paused DAG run end-to-end:
 *   {@code HumanReviewService.decide(APPROVE)} → {@code WorkflowStepResumeHandler.onDecided} →
 *   {@code WorkflowService.resumeWorkflowRun} → {@code resumeViaDag} →
 *   {@code DagWorkflowExecutor.resume}, which injects the settled node's output, delivers it to the
 *   pending successor, and finishes the run COMPLETED with the frontier cleared.
 *
 *   <p><b>Why the paused state is seeded.</b> Producing a real AGENT HITL pause offline needs a tool
 *   gate (FakeChatModel scripting), which {@code HitlResolveContinuesAgentRunE2ERuntimeTest} already
 *   covers for the agent path. This test seeds the paused DAG run (status AWAITING_HUMAN_REVIEW + a
 *   real {@link DagFrontier} where node A is paused and node B is pending) — the established pattern
 *   from {@code WorkflowRejectionCascadeRuntimeTest} — and then exercises the REAL service cascade +
 *   the REAL DAG resume, which is the actual gap. The {@code HumanReviewPending} row is created via
 *   the real {@link HumanReviewService#pauseFor}, not hand-inserted.
 *
 *   <p>Second assertion: the service-level double-settle guard — a second {@code decide} on the
 *   already-settled row is a no-op (it never re-dispatches to the handler), so the successor runs
 *   exactly once. Runs on the DAG engine's default-on configuration ({@code isDagRun} keys off the frontier).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagHumanReviewResumeCascadeRuntimeTest extends BaseIntegrationTest {

    @Autowired private HumanReviewService humanReviewService;
    @Autowired private WorkflowRunRepository workflowRuns;

    @Test
    void approveDecision_cascadesToDagResume_completesRunOnce() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        seedWorkflow(wf);
        String session = "sess-hr-cascade-" + UUID.randomUUID();
        seedSession(session);

        // A → B. A is "paused for human review"; B is the pending successor the resume must run.
        String aId = insertAgentStep(wf, 1, seedEchoAgent("A (echo)"));
        String bId = insertAgentStep(wf, 2, seedEchoAgent("B (echo)"));
        insertEdge(wf, aId, bId, null);

        // Persist the paused DAG run: A paused (PauseKind.REVIEW → AWAITING_HUMAN_REVIEW), B pending
        // with one outstanding predecessor token. currentPayload is what the operator-approved A
        // "produced" and what B will echo.
        String runId = "wfrun-" + UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setWorkflowId(wf);
        run.setSessionId(session);
        run.setOrgId("DEFAULT_SYSTEM_ORG");
        run.setStatus(RunStatus.AWAITING_HUMAN_REVIEW);
        run.setCurrentStepOrder(0);
        run.setCurrentPayload("approved-by-operator");
        run.setDagFrontier(new DagFrontier(
                List.of(),                  // pendingNodeIds — nothing ready-but-unstarted
                Map.of(bId, 1),             // remaining — B awaits one predecessor token
                Map.of(bId, 0),             // liveTokens
                Map.of(),                   // iteration
                Map.of(),                   // attempts
                Map.of(),                   // loopInput
                List.of(aId),               // pausedNodeIds — A is the settled node on resume
                PauseKind.REVIEW,
                Map.of()));                 // nestedPauses — plain (non-nested) pause
        workflowRuns.saveAndFlush(run);

        // Real pending row via the real service (subjectType WORKFLOW_STEP → WorkflowStepResumeHandler).
        HumanReviewPending pending = humanReviewService.pauseFor(
                HumanReviewSubjectType.WORKFLOW_STEP, aId, runId, "DEFAULT_SYSTEM_ORG",
                "step A awaiting review", null, null, "seed");

        // 1. APPROVE through the full cascade. decide → handler → resumeWorkflowRun → resumeViaDag (VT).
        humanReviewService.decide(pending.getId(), "DEFAULT_SYSTEM_ORG",
                HumanReviewDecision.APPROVE, null, "operator");

        Awaitility.await("DAG run resumes to COMPLETED via the HumanReview cascade")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> RunStatus.COMPLETED.name().equals(
                        jdbc.queryForObject("SELECT status FROM workflow_runs WHERE id = ?", String.class, runId)));

        String payload = jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE id = ?", String.class, runId);
        assertTrue(payload.contains("[B (echo)] approved-by-operator"),
                "successor B must run on the operator-approved payload: " + payload);
        assertEquals(1, attemptsFor(runId, bId), "successor B runs exactly once");
        assertNull(jdbc.queryForObject("SELECT dag_frontier::text FROM workflow_runs WHERE id = ?",
                String.class, runId), "frontier must be cleared once the run completes");

        // 2. Double-settle: a second decide on the already-settled row never re-dispatches → no re-run.
        humanReviewService.decide(pending.getId(), "DEFAULT_SYSTEM_ORG",
                HumanReviewDecision.APPROVE, null, "operator");
        // Give any (erroneous) second resume a chance to fire, then confirm B still ran exactly once.
        Awaitility.await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> attemptsFor(runId, bId) == 1);
        assertEquals(1, attemptsFor(runId, bId), "double-settle must not re-run the successor");
    }

    // ─── Helpers (echo harness) ────────────────────────────────────────────────

    private int attemptsFor(String runId, String nodeId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt), 0) FROM workflow_node_runs WHERE run_id = ? AND node_id = ?",
                Integer.class, runId, nodeId);
        return max == null ? 0 : max;
    }

    private void seedEchoModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('echo-test', 'Echo (test)', 'ECHO', 'echo-demo', false, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private String seedEchoAgent(String name) {
        String id = "agent-echo-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'echo-test', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, name);
        return id;
    }

    private void seedWorkflow(String workflowId) {
        jdbc.update("""
                INSERT INTO workflows (id, name, org_id, created_at, updated_at)
                VALUES (?, 'HR cascade resume', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, workflowId);
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'dag-hr-cascade-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private String insertAgentStep(String wf, int order, String agentId) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, ?, 'AGENT')", id, wf, order, agentId);
        return id;
    }

    private void insertEdge(String wf, String from, String to, String port) {
        jdbc.update("INSERT INTO workflow_edges (id, workflow_id, from_step_id, to_step_id, condition, created_at) "
                + "VALUES (?, ?, ?, ?, ?, now())", "edge-" + UUID.randomUUID(), wf, from, to, port);
    }
}
