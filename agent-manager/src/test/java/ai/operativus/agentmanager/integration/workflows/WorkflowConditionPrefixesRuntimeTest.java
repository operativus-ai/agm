package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import ai.operativus.agentmanager.integration.support.RecordingAgentOperations;
import ai.operativus.agentmanager.integration.support.RecordingAgentOperationsConfig;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: REQ-DR-6 — pins the CONDITION step dispatcher's new
 *   prefix branches (`jsonpath:` and `llm:`) end-to-end through
 *   {@code WorkflowService.executeWorkflowAsync}. The existing
 *   {@code contains:} / {@code length>} / {@code not_empty} prefixes are
 *   covered by {@code WorkflowsRuntimeTest.conditionStep_whenConditionReturnsFalse_skipsNextStep_andProceeds}.
 *
 *   <p>Semantics: when {@code evaluateCondition} returns TRUE the dispatcher
 *   continues to the next step. When FALSE it skips the next step. These
 *   tests pin both outcomes for each new prefix.
 *
 *   <p>Workflow shape: 3 steps — [CONDITION → AGENT (target) → AGENT (tail)].
 *   Condition TRUE  → executes both AGENT steps (2 runner calls).
 *   Condition FALSE → skips the target, executes only the tail (1 runner call).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowConditionPrefixesRuntimeTest extends BaseIntegrationTest {

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

    // ─── jsonpath: prefix ────────────────────────────────────────────────────

    @Test
    void jsonPathCondition_truthyResolution_doesNotSkipNextStep() {
        HttpHeaders auth = newCaller("jp-truthy");
        String workflowId = createWorkflow(auth, "JSONPath truthy");
        String tgt = seedAgent("jp-truthy-tgt");
        String tail = seedAgent("jp-truthy-tail");

        // Condition resolves $.flag against the workflow's initial input.
        insertConditionStep(workflowId, 1, "jsonpath:$.flag");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-jp-truthy");
        runner.scriptResponse("tgt-output");
        runner.scriptResponse("tail-output");

        // Initial input is JSON with $.flag = true → truthy → next step runs.
        runWorkflow(auth, workflowId, "sess-jp-truthy", "{\"flag\": true}");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "truthy jsonpath: must NOT skip; expected 2 agent calls, got: " + runner.calls);
    }

    @Test
    void jsonPathCondition_pathMissing_skipsNextStep() {
        HttpHeaders auth = newCaller("jp-missing");
        String workflowId = createWorkflow(auth, "JSONPath missing");
        String tgt = seedAgent("jp-missing-tgt");
        String tail = seedAgent("jp-missing-tail");

        insertConditionStep(workflowId, 1, "jsonpath:$.absent");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-jp-missing");
        runner.scriptResponse("tail-output");

        // $.absent path-not-found → false → skip step 2; only step 3 runs.
        runWorkflow(auth, workflowId, "sess-jp-missing", "{\"other\": \"value\"}");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(1, runner.calls.size(),
                "missing path must evaluate to false (skip target); got: " + runner.calls);
    }

    @Test
    void jsonPathCondition_freeTextInputWrappedAsText_resolvesViaText() {
        HttpHeaders auth = newCaller("jp-text");
        String workflowId = createWorkflow(auth, "JSONPath text-wrap");
        String tgt = seedAgent("jp-text-tgt");
        String tail = seedAgent("jp-text-tail");

        insertConditionStep(workflowId, 1, "jsonpath:$.text");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-jp-text");
        runner.scriptResponse("tgt-output");
        runner.scriptResponse("tail-output");

        // Non-JSON input gets wrapped as {"text": "..."} so $.text resolves to
        // the input string itself — truthy (non-empty) → no skip.
        runWorkflow(auth, workflowId, "sess-jp-text", "go ahead");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "non-empty wrapped-text must evaluate truthy; got: " + runner.calls);
    }

    // ─── llm: prefix ─────────────────────────────────────────────────────────

    @Test
    void llmCondition_responseYes_doesNotSkipNextStep() {
        HttpHeaders auth = newCaller("llm-yes");
        String workflowId = createWorkflow(auth, "LLM yes");
        String tgt = seedAgent("llm-yes-tgt");
        String tail = seedAgent("llm-yes-tail");

        insertConditionStep(workflowId, 1, "llm:Is the customer angry?");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-llm-yes");
        chat.respondWith("yes");
        runner.scriptResponse("tgt-output");
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-llm-yes", "I want my refund NOW!!");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "'yes' from LLM evaluator must NOT skip; got: " + runner.calls);
        assertEquals(1, chat.receivedPrompts().size(),
                "exactly one prompt must reach FakeChatModel for the condition");
    }

    @Test
    void llmCondition_responseNo_skipsNextStep() {
        HttpHeaders auth = newCaller("llm-no");
        String workflowId = createWorkflow(auth, "LLM no");
        String tgt = seedAgent("llm-no-tgt");
        String tail = seedAgent("llm-no-tail");

        insertConditionStep(workflowId, 1, "llm:Is the customer angry?");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-llm-no");
        chat.respondWith("no");
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-llm-no", "Just a quick question, thanks");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(1, runner.calls.size(),
                "'no' from LLM evaluator must skip target; got: " + runner.calls);
    }

    @Test
    void llmCondition_chatClientFailure_evaluatesFalseAndSkips() {
        HttpHeaders auth = newCaller("llm-err");
        String workflowId = createWorkflow(auth, "LLM err");
        String tgt = seedAgent("llm-err-tgt");
        String tail = seedAgent("llm-err-tail");

        insertConditionStep(workflowId, 1, "llm:Is anything ok?");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-llm-err");
        chat.respondWith(p -> { throw new RuntimeException("provider hiccup"); });
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-llm-err", "anything");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(1, runner.calls.size(),
                "ChatClient RuntimeException must evaluate to false (defensive skip); got: "
                        + runner.calls);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "cond-prefix-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-cond-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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
                """, id, "Condition prefix agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'cond-prefix-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private void insertConditionStep(String workflowId, int order, String expression) {
        // CONDITION step stores its expression in the agent_id column (existing convention).
        // workflow_steps.agent_id has FK → agents.id, so seed a placeholder agents row
        // named with the expression. WorkflowService never invokes AgentOperations on
        // CONDITION steps; the row exists only to satisfy the FK.
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, 'cond-expr-placeholder', 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, expression);
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                        + "VALUES (?, ?, ?, ?, 'CONDITION')",
                UUID.randomUUID().toString(), workflowId, order, expression);
    }

    private void insertAgentStep(String workflowId, int order, String agentId) {
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, ?, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, order, agentId);
    }

    private void runWorkflow(HttpHeaders auth, String workflowId, String sessionId, String input) {
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input, "sessionId", sessionId), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = String.valueOf(accepted.getBody().get("jobId"));
        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
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
