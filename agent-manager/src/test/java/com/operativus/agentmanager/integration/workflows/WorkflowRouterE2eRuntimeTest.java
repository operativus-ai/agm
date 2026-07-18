package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import com.operativus.agentmanager.integration.support.RecordingAgentOperations;
import com.operativus.agentmanager.integration.support.RecordingAgentOperationsConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: End-to-end runtime coverage for REQ-DR-4 (Workflow
 *   Router Step). Drives the full {@code WorkflowService.executeWorkflowAsync}
 *   path through a ROUTER step using each of the three selector strategies
 *   (RULE / HITL / LLM) plus the {@code POST /api/v1/workflows/runs/{runId}/continue}
 *   resume endpoint.
 *
 *   <p>Workflow shape used by these tests: a flat ordered list of 4 steps —
 *   <pre>
 *     step1 (order=1, AGENT)   ─ produces the classification input
 *     step2 (order=2, ROUTER)  ─ branches to step3 or step4
 *     step3 (order=3, AGENT)   ─ "approve" target
 *     step4 (order=4, AGENT)   ─ "reject" target
 *   </pre>
 *
 *   <p>Routing semantics in the existing flat-step engine: ROUTER jumps the
 *   loop cursor to the chosen step, then continues linearly. Choosing the
 *   LATER step (step4) skips step3; choosing the EARLIER step (step3) jumps
 *   to step3 and then continues into step4. Mutually-exclusive branches
 *   require placing the "skip" target after its alternatives in step_order.
 *   This is a constraint of the flat-list engine and is documented on
 *   {@code StepActionType.ROUTER}; a future DAG engine (REQ-DR-5) will
 *   address it.
 *
 *   <p>Flag wiring: {@code agm.workflow.router.enabled=true} via
 *   {@link TestPropertySource}. Without the flag, ROUTER demotes to AGENT
 *   (pinned in PR-3's pre-screen logic) and these tests would not exercise
 *   the new dispatcher case.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
@TestPropertySource(properties = "agm.workflow.router.enabled=true")
public class WorkflowRouterE2eRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;
    @Autowired private RecordingAgentOperations runner;
    @Autowired private FakeChatModel chat;

    @BeforeEach
    void seedAndReset() {
        runner.reset();
        chat.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // ─── RULE selector ───────────────────────────────────────────────────────

    @Test
    void ruleSelector_picksLaterStep_skipsEarlierBranch() {
        HttpHeaders auth = newCaller("rule-skip");
        String workflowId = createWorkflow(auth, "Rule skip flow");
        String agentA = seedAgent("rule-a");
        String agent3 = seedAgent("rule-3");
        String agent4 = seedAgent("rule-4");

        String step1Id = insertAgentStep(workflowId, 1, agentA);
        String step3Id = insertAgentStep(workflowId, 3, agent3);
        String step4Id = insertAgentStep(workflowId, 4, agent4);
        // ROUTER targets choose 'reject' → step4; skipping step3.
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("approve", step3Id);
        choices.put("reject", step4Id);
        insertRouterStep(workflowId, 2,
                new RouterStepConfig(RouteSelectorType.RULE, "$.text", choices, null));

        seedSession("sess-rule-skip");
        // step1 emits "reject" — RULE evaluates $.text against the wrapped text
        // payload and matches the "reject" choice key, branching to step4.
        runner.scriptResponse("reject");
        runner.scriptResponse("step4-output");

        runWorkflow(auth, workflowId, "sess-rule-skip", "go");

        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertAll("rule selector forward-skip",
                () -> assertEquals(2, runner.calls.size(),
                        "expected step1 + step4 only (step3 skipped); got " + runner.calls.size()
                                + " calls: " + runner.calls),
                () -> assertEquals(agentA, runner.calls.get(0).agentId(),
                        "first call must be step1's agent"),
                () -> assertEquals(agent4, runner.calls.get(1).agentId(),
                        "second call must be step4's agent (branch target)"),
                () -> assertEquals("reject", runner.calls.get(1).input(),
                        "step4 input must be step1's output ('reject') — the prior-payload "
                                + "carries through the router step"));
    }

    @Test
    void ruleSelector_noMatchAndNoDefault_failsRunWithReason() {
        HttpHeaders auth = newCaller("rule-fail");
        String workflowId = createWorkflow(auth, "Rule no-match flow");
        String agentA = seedAgent("rule-fail-a");
        String agent3 = seedAgent("rule-fail-3");
        String agent4 = seedAgent("rule-fail-4");

        insertAgentStep(workflowId, 1, agentA);
        String step3Id = insertAgentStep(workflowId, 3, agent3);
        String step4Id = insertAgentStep(workflowId, 4, agent4);
        insertRouterStep(workflowId, 2, new RouterStepConfig(
                RouteSelectorType.RULE, "$.text",
                Map.of("approve", step3Id, "reject", step4Id), null));

        seedSession("sess-rule-fail");
        // "escalate" matches neither choice key; no defaultChoice → run fails.
        runner.scriptResponse("escalate");

        // A FAILED run is a non-retryable job outcome since #1167 — the job lands in the DLQ
        // immediately (one run row, no re-execution), so this deliberately-failing scenario must
        // await job FAILURE, not the shared success-awaiting runWorkflow helper.
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "go", "sessionId", "sess-rule-fail"), auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        jobs.processNow();
        jobs.awaitJobFailure(String.valueOf(accepted.getBody().get("jobId")), Duration.ofSeconds(10));

        awaitWorkflowStatus(workflowId, "FAILED");
        assertEquals(1, runner.calls.size(),
                "only step1 should have run; the dispatcher must abort at the ROUTER step");
    }

    @Test
    void ruleSelector_noMatchWithDefaultChoice_branchesToDefault() {
        HttpHeaders auth = newCaller("rule-default");
        String workflowId = createWorkflow(auth, "Rule default flow");
        String agentA = seedAgent("rule-default-a");
        String agent3 = seedAgent("rule-default-3");
        String agent4 = seedAgent("rule-default-4");

        insertAgentStep(workflowId, 1, agentA);
        String step3Id = insertAgentStep(workflowId, 3, agent3);
        String step4Id = insertAgentStep(workflowId, 4, agent4);
        // defaultChoice="reject" means an unrecognized leaf falls through to step4.
        insertRouterStep(workflowId, 2, new RouterStepConfig(
                RouteSelectorType.RULE, "$.text",
                Map.of("approve", step3Id, "reject", step4Id), "reject"));

        seedSession("sess-rule-default");
        runner.scriptResponse("escalate"); // unmatched → defaultChoice → step4
        runner.scriptResponse("step4-output");

        runWorkflow(auth, workflowId, "sess-rule-default", "go");

        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size());
        assertEquals(agent4, runner.calls.get(1).agentId(),
                "defaultChoice='reject' must branch to step4");
    }

    // ─── HITL selector ───────────────────────────────────────────────────────

    @Test
    void hitlSelector_suspendsRunAtAwaitingRouteSelection_andContinueResumes() {
        HttpHeaders auth = newCaller("hitl");
        String workflowId = createWorkflow(auth, "HITL flow");
        String agentA = seedAgent("hitl-a");
        String agent3 = seedAgent("hitl-3");
        String agent4 = seedAgent("hitl-4");

        insertAgentStep(workflowId, 1, agentA);
        String step3Id = insertAgentStep(workflowId, 3, agent3);
        String step4Id = insertAgentStep(workflowId, 4, agent4);
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("approve", step3Id);
        choices.put("reject", step4Id);
        insertRouterStep(workflowId, 2,
                new RouterStepConfig(RouteSelectorType.HITL, null, choices, null));

        seedSession("sess-hitl");
        runner.scriptResponse("step1-pretext");
        // Second script entry is the AGENT step the resume will eventually invoke
        // (step4 — chosen via /continue below).
        runner.scriptResponse("step4-output");

        String runId = runWorkflow(auth, workflowId, "sess-hitl", "go");

        // First, the workflow must halt at AWAITING_ROUTE_SELECTION.
        awaitWorkflowStatus(workflowId, "AWAITING_ROUTE_SELECTION");
        assertEquals(1, runner.calls.size(),
                "only step1 should have run before the HITL suspension");

        // Resolve runId from DB (returned URL was the job id).
        String pausedRunId = jdbc.queryForObject(
                "SELECT id FROM workflow_runs WHERE workflow_id = ?",
                String.class, workflowId);

        // POST /continue with the 'reject' choice → resume into step4.
        ResponseEntity<Map<String, Object>> continueResp = rest.exchange(
                url("/api/v1/workflows/runs/" + pausedRunId + "/continue"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("choiceKey", "reject"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, continueResp.getStatusCode());
        String continueJobId = String.valueOf(continueResp.getBody().get("jobId"));

        jobs.processNow();
        jobs.awaitJobSuccess(continueJobId, Duration.ofSeconds(10));

        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "expected step1 + step4 after continue; got: " + runner.calls);
        assertEquals(agent4, runner.calls.get(1).agentId(),
                "second call must be step4's agent (the 'reject' branch target)");
    }

    // Invalid choiceKey behavior is pinned at unit level by
    // WorkflowServiceTest.continueAfterRouteSelection_invalidChoiceKey_throws (PR-4):
    // an e2e duplicate adds runtime coupling to the queue's retry/DLQ policy without
    // adding signal — the dispatcher contract under test is "throw IAE with reason",
    // which the unit test pins directly.

    // ─── LLM selector ────────────────────────────────────────────────────────

    @Test
    void llmSelector_classifiesPriorOutput_andBranchesToChosenStep() {
        HttpHeaders auth = newCaller("llm");
        String workflowId = createWorkflow(auth, "LLM flow");
        String agentA = seedAgent("llm-a");
        String agent3 = seedAgent("llm-3");
        String agent4 = seedAgent("llm-4");

        insertAgentStep(workflowId, 1, agentA);
        String step3Id = insertAgentStep(workflowId, 3, agent3);
        String step4Id = insertAgentStep(workflowId, 4, agent4);
        // selectorExpression here is the operator-authored classification instruction.
        String selectorInstruction = "Is the customer asking for a refund? Output 'reject' if yes else 'approve'.";
        insertRouterStep(workflowId, 2, new RouterStepConfig(
                RouteSelectorType.LLM,
                selectorInstruction,
                Map.of("approve", step3Id, "reject", step4Id), null));

        seedSession("sess-llm");
        runner.scriptResponse("Customer wants a refund.");
        // FakeChatModel returns 'reject' — LLM selector normalizes + matches the
        // declared choice key, branching to step4 (skip step3).
        chat.respondWith("reject");
        runner.scriptResponse("step4-output");

        runWorkflow(auth, workflowId, "sess-llm", "go");

        awaitWorkflowStatus(workflowId, "COMPLETED");
        // Count only THIS run's classification prompts (matched by its unique selectorExpression).
        // The FakeChatModel bean is shared across this class's context; a sibling run's job draining
        // through the same queue can land a stray prompt on the shared instance, so a raw
        // receivedPrompts().size() is non-deterministic under full-suite ordering. Filtering by this
        // run's instruction still catches a genuine duplicate dispatch of THIS classification (→ 2)
        // and the not-invoked case (→ 0); it only excludes unrelated cross-run bleed.
        long thisRunClassificationPrompts = chat.receivedPrompts().stream()
                .filter(p -> p.getContents().contains(selectorInstruction))
                .count();
        assertAll("llm selector e2e",
                () -> assertEquals(2, runner.calls.size(),
                        "expected step1 + step4 only; got: " + runner.calls),
                () -> assertEquals(agent4, runner.calls.get(1).agentId(),
                        "LLM selector returned 'reject' → step4 must be the branch target"),
                () -> assertEquals(1, thisRunClassificationPrompts,
                        "exactly one classification prompt for THIS run's selector must reach FakeChatModel; "
                                + "more = duplicate dispatch; zero = LLM selector wasn't invoked"));
    }

    @Test
    void llmSelector_responseNotInChoices_fallsThroughToDefaultChoice() {
        HttpHeaders auth = newCaller("llm-default");
        String workflowId = createWorkflow(auth, "LLM default flow");
        String agentA = seedAgent("llm-default-a");
        String agent3 = seedAgent("llm-default-3");
        String agent4 = seedAgent("llm-default-4");

        insertAgentStep(workflowId, 1, agentA);
        String step3Id = insertAgentStep(workflowId, 3, agent3);
        String step4Id = insertAgentStep(workflowId, 4, agent4);
        insertRouterStep(workflowId, 2, new RouterStepConfig(
                RouteSelectorType.LLM, null,
                Map.of("approve", step3Id, "reject", step4Id), "approve"));

        seedSession("sess-llm-default");
        runner.scriptResponse("Customer wants something else.");
        chat.respondWith("maybe");                 // not in choices → defaultChoice="approve"
        runner.scriptResponse("step3-output");
        runner.scriptResponse("step4-output");

        runWorkflow(auth, workflowId, "sess-llm-default", "go");

        awaitWorkflowStatus(workflowId, "COMPLETED");
        // 'approve' → step3 (order 3) → dispatcher then continues into step4 (order 4).
        // The "skip" effect only fires when the target is later than the natural next.
        assertEquals(3, runner.calls.size(),
                "expected step1 + step3 + step4; got " + runner.calls);
        assertEquals(agent3, runner.calls.get(1).agentId());
        assertEquals(agent4, runner.calls.get(2).agentId());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "router-e2e-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-router-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private String seedAgent(String label) {
        String id = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, "Router e2e agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'router-e2e-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private String insertAgentStep(String workflowId, int order, String agentId) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, ?, ?, 'AGENT')",
                id, workflowId, order, agentId);
        return id;
    }

    private String insertRouterStep(String workflowId, int order, RouterStepConfig config) {
        String id = "step-" + UUID.randomUUID();
        String configJson;
        try {
            configJson = json.writeValueAsString(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize RouterStepConfig", e);
        }
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, action, router_config) "
                        + "VALUES (?, ?, ?, 'ROUTER', ?::jsonb)",
                id, workflowId, order, configJson);
        return id;
    }

    /** Returns the workflow_run id (resolved post-dispatch from the row). */
    private String runWorkflow(HttpHeaders auth, String workflowId, String sessionId, String input) {
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input, "sessionId", sessionId), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = String.valueOf(accepted.getBody().get("jobId"));
        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
        return jobId;
    }

    private void awaitWorkflowStatus(String workflowId, String expectedStatus) {
        Awaitility.await("workflow_runs.status=" + expectedStatus + " for " + workflowId)
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = ?",
                            Integer.class, workflowId, expectedStatus);
                    return n != null && n >= 1;
                });
    }
}
