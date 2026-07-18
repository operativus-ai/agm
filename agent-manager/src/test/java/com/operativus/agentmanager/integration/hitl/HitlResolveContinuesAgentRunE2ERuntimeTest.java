package com.operativus.agentmanager.integration.hitl;

import com.operativus.agentmanager.control.service.ApprovalService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.ApprovalDTO;
import com.operativus.agentmanager.core.model.DecisionPackage;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Closes the gap explicitly flagged in
 *   {@code HitlResumeRuntimeTest}'s class-level Javadoc — "the virtual-thread
 *   {@code continueRun} is fire-and-forget and NOT asserted on here". This test
 *   drives the FULL chat → HITL pause → resolve → continueRun → finalize loop and
 *   verifies the {@code agent_runs} row reaches its terminal state in the resumed
 *   chat path.
 *
 *   <p>End-to-end flow exercised:
 *   <ol>
 *     <li>Seed an agent + an {@code agent_runs} row in {@code PAUSED} state with a
 *         valid {@code requiredAction} JSON (the contract owner is
 *         {@code HitlAdvisor.requireApprovalForTool}'s {@code RequiredAction} record).</li>
 *     <li>Create the approval row via {@code ApprovalService.createApprovalRequest}
 *         so {@code payload_hash} is computed on the jsonb-round-tripped value (the
 *         fix pinned by {@code ApprovalsCreateViaServiceResolveRuntimeTest}).</li>
 *     <li>{@code POST /api/v1/approvals/{id}/resolve} with the user's decision.</li>
 *     <li>{@code ApprovalService.resolveApprovalForOrg} spawns a virtual thread that
 *         calls {@code AgentOperations.continueRun(runId, decision)}.</li>
 *     <li>{@code AgentService.continueRun} re-binds {@code preAllocatedRunId} +
 *         {@code approvedTools} and reruns the agent; the PAUSED row gets reused
 *         instead of duplicated.</li>
 *     <li>{@code AgentRunFinalizer.finalizeRun} transitions the row to its terminal
 *         state — {@code COMPLETED} on APPROVED, {@code CANCELLED} on REJECTED.</li>
 *   </ol>
 *
 *   <p>This is the contract the FE chat UX relies on: the Stop/Approve button on a
 *   paused {@code MessageBubble} → resolve → resumed final content. Without this
 *   pin, a regression in the resolve→continueRun handoff (e.g. ScopedValue rebinding
 *   drift, finalizer guard inversion) could silently leave runs PAUSED forever.
 *
 *   <p>Two pins:
 *   <ol>
 *     <li>APPROVED decision → PAUSED row resumes, chat model is called, row lands on
 *         COMPLETED with the resumed content as output.</li>
 *     <li>REJECTED decision → PAUSED row finalizes to CANCELLED with the
 *         "User rejected the requested action." cancellation reason. Chat model is
 *         NOT called.</li>
 *   </ol>
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class HitlResolveContinuesAgentRunE2ERuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static final Duration RESUME_TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ApprovalService approvalService;
    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        fakeModel.reset();
    }

    // Pin: APPROVED resolve → continueRun reruns the agent → PAUSED row finalizes
    // to COMPLETED with the resumed content. This is the happy-path E2E that the
    // existing HitlResumeRuntimeTest explicitly disclaimed.
    @Test
    void approvedResolve_continuesPausedRun_andFinalizesRowAsCompletedWithResumedContent() {
        String orgId = "org-hitl-e2e-approve";
        String username = "hitl-e2e-approver";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);

        Fixture fx = seedPausedRun("approve", orgId, username);

        // Create the approval via the service so payload_hash is computed on the
        // jsonb-round-tripped value (cf. ApprovalsCreateViaServiceResolveRuntimeTest fix).
        ApprovalDTO approval = bindOrgIdAnd(orgId, () ->
                approvalService.createApprovalRequest(
                        fx.runId, fx.sessionId, fx.agentId,
                        "delete_database",
                        "{\"db\":\"prod\"}",
                        "Needs approval before deleting prod DB",
                        username,
                        "trace-resume-approve",
                        "Deletes prod DB",
                        DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE));

        // Pre-flight: row is PAUSED, FakeChatModel hasn't been called yet.
        assertEquals("PAUSED", jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId),
                "fixture precondition: run row must be PAUSED before resolve");
        assertEquals(0, fakeModel.receivedPrompts().size(),
                "no chat call must have fired pre-resolve");

        // Script the chat model's response for the resumed run. continueRun's bound
        // ScopedValue includes approvedTools={delete_database} so HitlAdvisor will let
        // the tool through; the synthesized text is what the user ultimately sees.
        fakeModel.respondWith("Production DB deleted. Resumed final response.");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approval.id() + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "resolve must return 200 on a PENDING approval");

        // The virtual thread spawned by resolveApprovalForOrg runs continueRun
        // asynchronously. Poll until the row reaches its terminal state.
        Awaitility.await().atMost(RESUME_TIMEOUT).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
            assertEquals("COMPLETED", status,
                    "APPROVED resolve must drive continueRun → finalize the paused row as "
                            + "COMPLETED within the timeout. Still PAUSED here means the "
                            + "VT-spawned continueRun never landed; FAILED means the resumed "
                            + "run threw; CANCELLED means the action enum string drifted "
                            + "from RunStatus.APPROVED.name() (cf. F18 in AgentService:849).");
        });

        // The resumed content must be the scripted reply, NOT the original user input.
        String finalOutput = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, fx.runId);
        assertEquals("Production DB deleted. Resumed final response.", finalOutput,
                "the run's output column must carry the RESUMED chat response, not the "
                        + "original prompt or a placeholder — proves continueRun actually "
                        + "re-ran the agent and the finalizer wrote the new content through");

        assertTrue(fakeModel.receivedPrompts().size() >= 1,
                "FakeChatModel must have received at least one prompt during the resume — "
                        + "if 0, the VT-spawned continueRun never reached the model boundary");
    }

    // Pin: REJECTED resolve → no continueRun re-execution → PAUSED row finalizes to
    // CANCELLED with the documented cancellation reason. Chat model is NOT called.
    @Test
    void rejectedResolve_finalizesPausedRunAsCancelled_withoutReinvokingChatModel() {
        String orgId = "org-hitl-e2e-reject";
        String username = "hitl-e2e-rejecter";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);

        Fixture fx = seedPausedRun("reject", orgId, username);

        ApprovalDTO approval = bindOrgIdAnd(orgId, () ->
                approvalService.createApprovalRequest(
                        fx.runId, fx.sessionId, fx.agentId,
                        "delete_database",
                        "{\"db\":\"prod\"}",
                        "Needs approval before deleting prod DB",
                        username,
                        "trace-resume-reject",
                        "Deletes prod DB",
                        DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE));

        // Script a response that should NEVER be observed — if the rejection path
        // accidentally re-runs the agent, the test will see this in fakeModel.receivedPrompts.
        fakeModel.respondWith("THIS SHOULD NOT BE SEEN — REJECTION SHOULD NOT RERUN AGENT");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approval.id() + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "REJECTED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Awaitility.await().atMost(RESUME_TIMEOUT).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
            assertEquals("CANCELLED", status,
                    "REJECTED resolve must finalize the paused row as CANCELLED (per "
                            + "AgentService.continueRun:913). COMPLETED here means the agent "
                            + "got re-invoked on rejection (regression); still PAUSED means "
                            + "the VT-spawned continueRun never landed.");
        });

        // AgentService.continueRun:913 calls finalizeRun(runId, CANCELLED,
        // "User rejected the requested action.", null, null). The third positional arg is
        // the OUTPUT column (see AgentRunFinalizer:61), so the literal lands in output —
        // not error_message. Pin both: output carries the literal, error_message stays null.
        String finalOutput = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, fx.runId);
        assertEquals("User rejected the requested action.", finalOutput,
                "cancellation reason must use the documented literal in the output column — "
                        + "pin protects against drift that would silently change the FE's "
                        + "error toast text. (finalizeRun's third arg = output, per "
                        + "AgentRunFinalizer:61.)");
        assertNotEquals("THIS SHOULD NOT BE SEEN — REJECTION SHOULD NOT RERUN AGENT", finalOutput,
                "the rejected-prompt text must NEVER appear in the run's output");

        // The chat model must NOT have been invoked during the reject path.
        assertEquals(0, fakeModel.receivedPrompts().size(),
                "REJECTED resolve must NOT re-invoke the chat model — observing a prompt "
                        + "here means the rejection path is paying for an unintended LLM call");
    }

    // ─── helpers ───

    private record Fixture(String agentId, String sessionId, String runId) {}

    private Fixture seedPausedRun(String label, String orgId, String userId) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String agentId = "agent-hitl-e2e-" + label + "-" + tag;
        String sessionId = "session-hitl-e2e-" + label + "-" + tag;
        String runId = "run-hitl-e2e-" + label + "-" + tag;

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "HITL E2E " + label + " agent", orgId);

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, userId, orgId, agentId);

        // requiredAction is a JSON-serialized RequiredAction record. AgentService.continueRun
        // parses this to extract the toolName for the approvedTools scope binding.
        String requiredAction = "{\"tool\":\"delete_database\","
                + "\"arguments\":{\"db\":\"prod\"},"
                + "\"decisionTier\":\"TIER_3_DESTRUCTIVE\","
                + "\"reason\":\"awaits approval\"}";

        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, input, user_id, org_id,
                                        status, required_action, created_at, updated_at)
                VALUES (?, ?, ?, 'Please delete prod DB.', ?, ?,
                        'PAUSED', ?::jsonb, now(), now())
                """, runId, agentId, sessionId, userId, orgId, requiredAction);

        return new Fixture(agentId, sessionId, runId);
    }

    private static <T> T bindOrgIdAnd(String orgId, Callable<T> body) {
        try {
            return ScopedValue.where(AgentContextHolder.orgId, orgId).call(body::call);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }
}
