package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Exhaustively pin the {@code ApprovalDTO} wire shape returned by
 *   {@code GET /api/v1/approvals/pending}. ApprovalDTO is a 15-field record consumed by
 *   the FE HITL inbox; silent renames or drops here break operator UX without any
 *   compile-time warning on the FE side (which fetches as JSON, not via a generated
 *   client). Existing tests verify the page envelope ({@link ApprovalsPendingPaginationContractRuntimeTest})
 *   and the {@code decisionTier} field ({@link ApprovalsTier2FinOpsRuntimeTest}) but no
 *   test asserts the full DTO field map.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * ApprovalDTO fields pinned (15):
 *   id, runId, workflowRunId, agentId, toolName, toolArguments, status, requestedBy,
 *   resolvedBy, contextualMessage, decisionTier, reasoningTrace, impactAssessment,
 *   createdAt, resolvedAt.
 *
 * Any future field add lands as a passing entry here (the test asserts presence + value
 * of known fields, allowing additional fields). Any future field RENAME or DROP fails
 * loudly — pointing the contributor at the FE consumer that depends on it.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsPendingResponseShapeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P-N-1 — Full DTO field map for a single PENDING approval. Pins value + presence
    // for every field. Pre-resolution fields (resolvedBy, resolvedAt) must be null.
    @Test
    void pendingApproval_responseRowCarriesAllExpectedApprovalDtoFields() {
        String orgId = "org-shape-pending";
        HttpHeaders auth = registerLoginWithOrg("shape-pending", orgId);

        Fixture fx = seedAgentSession("shape-pending");
        String approvalId = "approval-shape-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, contextual_message,
                                       decision_tier, reasoning_trace, impact_assessment,
                                       org_id, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'destructive-tool',
                        ?::jsonb, ?, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, now(), now(), 0)
                """,
                approvalId, fx.runId, fx.sessionId, fx.agentId,
                "{\"db\":\"prod\"}", "shape-requester", "Approval needed for prod ops",
                "Test reasoning trace string",
                "Test impact assessment string",
                orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending?size=50"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        Map<String, Object> row = content.stream()
                .filter(m -> approvalId.equals(m.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "seeded PENDING approval must appear in /pending response"));

        assertAll("ApprovalDTO field map — populated PENDING row",
                () -> assertEquals(approvalId, row.get("id")),
                () -> assertEquals(fx.runId, row.get("runId"),
                        "runId is the FE bridge to agent_runs; never drop or rename it"),
                () -> assertNull(row.get("workflowRunId"),
                        "workflowRunId must be null for non-workflow approvals"),
                () -> assertEquals(fx.agentId, row.get("agentId")),
                () -> assertEquals("destructive-tool", row.get("toolName")),
                () -> assertNotNull(row.get("toolArguments"),
                        "toolArguments must be present (jsonb may normalize whitespace)"),
                () -> assertEquals("PENDING", row.get("status")),
                () -> assertEquals("shape-requester", row.get("requestedBy")),
                () -> assertNull(row.get("resolvedBy"),
                        "resolvedBy must be null on PENDING rows"),
                () -> assertEquals("Approval needed for prod ops", row.get("contextualMessage")),
                () -> assertEquals("TIER_3_DESTRUCTIVE", row.get("decisionTier")),
                () -> assertEquals("Test reasoning trace string", row.get("reasoningTrace")),
                () -> assertEquals("Test impact assessment string", row.get("impactAssessment")),
                () -> assertNotNull(row.get("createdAt"),
                        "createdAt must be populated by the persistence layer"),
                () -> assertNull(row.get("resolvedAt"),
                        "resolvedAt must be null on PENDING rows"),
                () -> assertTrue(row.containsKey("id")
                                && row.containsKey("runId")
                                && row.containsKey("workflowRunId")
                                && row.containsKey("agentId")
                                && row.containsKey("toolName")
                                && row.containsKey("toolArguments")
                                && row.containsKey("status")
                                && row.containsKey("requestedBy")
                                && row.containsKey("resolvedBy")
                                && row.containsKey("contextualMessage")
                                && row.containsKey("decisionTier")
                                && row.containsKey("reasoningTrace")
                                && row.containsKey("impactAssessment")
                                && row.containsKey("createdAt")
                                && row.containsKey("resolvedAt"),
                        "all 15 ApprovalDTO keys must be present in the wire response — "
                                + "any silent drop here breaks the FE HITL inbox without a compile error"));
    }

    // P-N-2 — Workflow-bound approval populates workflowRunId; pure-agent approval has
    // null. Pins the FE's ability to distinguish "approval from workflow step" from
    // "approval from direct agent run" — different UX paths.
    @Test
    void workflowBoundApproval_responseCarriesWorkflowRunId_pureAgentRowHasNull() {
        String orgId = "org-shape-wf";
        HttpHeaders auth = registerLoginWithOrg("shape-wf", orgId);

        // Seed workflow + workflow_run for the workflow-bound approval.
        String workflowId = "wf-shape-" + UUID.randomUUID();
        String workflowRunId = "wfrun-shape-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflows (id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, workflowId, "Shape WF", "test fixture");

        Fixture wfFx = seedAgentSession("shape-wf-bound");
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status, current_step_order,
                                            org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'PAUSED', 1, ?, now(), now())
                """, workflowRunId, workflowId, wfFx.sessionId, orgId);

        String wfApprovalId = "approval-wf-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, workflow_run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 'wf-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                wfApprovalId, wfFx.runId, workflowRunId, wfFx.sessionId, wfFx.agentId,
                "{\"k\":\"v\"}", "wf-requester", orgId);

        // Seed a sibling non-workflow approval in the same org.
        Fixture plainFx = seedAgentSession("shape-wf-pure");
        String plainApprovalId = "approval-pure-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'plain-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                plainApprovalId, plainFx.runId, plainFx.sessionId, plainFx.agentId,
                "{\"k\":\"v\"}", "plain-requester", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending?size=50"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        Map<String, Object> wfRow = content.stream()
                .filter(m -> wfApprovalId.equals(m.get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("workflow-bound row missing"));
        Map<String, Object> plainRow = content.stream()
                .filter(m -> plainApprovalId.equals(m.get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("pure-agent row missing"));

        assertAll("workflowRunId discriminates workflow-bound from pure-agent approvals",
                () -> assertEquals(workflowRunId, wfRow.get("workflowRunId"),
                        "workflow-bound row must carry workflowRunId in the DTO"),
                () -> assertNull(plainRow.get("workflowRunId"),
                        "pure-agent row must have null workflowRunId — FE renders different UX based on this"),
                () -> assertEquals(wfFx.runId, wfRow.get("runId"),
                        "runId is always the agent-run reference; workflowRunId is in addition, not replacement"));
    }

    // ─── helpers ───

    private record Fixture(String agentId, String sessionId, String runId) {}

    private Fixture seedAgentSession(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Shape Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        return new Fixture(agentId, sessionId, runId);
    }
}
