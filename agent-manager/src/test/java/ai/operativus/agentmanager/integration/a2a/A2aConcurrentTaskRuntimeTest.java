package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Concurrent inbound A2A task isolation. Multiple peers can submit
 *   tasks to AGM in parallel; the executor spawns one virtual thread per task and tracks
 *   in-flight state in {@code activeTaskRuns} + {@code activeTaskThreads} (ConcurrentHashMap).
 *   This test fires N tasks against the same agent simultaneously and asserts:
 *   <ul>
 *     <li>Each task produces its own SUBMITTED + WORKING + COMPLETED audit chain
 *         keyed by its own taskId — no cross-contamination between concurrent
 *         tasks via shared mutable state.</li>
 *     <li>The {@code agent_runs} table has exactly N COMPLETED rows for the target agent
 *         — no run record was dropped, merged, or duplicated under contention.</li>
 *     <li>Every SSE response body carries that task's COMPLETED event with its own
 *         scripted output — proves the executor's per-task emitter is correctly
 *         bound and didn't leak content across tasks.</li>
 *   </ul>
 *
 *   {@code FakeChatModel} responds via a content-aware {@code Function<Prompt, ChatResponse>}
 *   to side-step thread-safety of its scripted-response deque under concurrent {@code call(Prompt)}
 *   — same pattern as {@code EndToEndOrchestrationRuntimeTest}'s Swarm case.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aConcurrentTaskRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Five concurrent A2A tasks against the same target agent. Each task has
     * its own taskId and its own scripted input (carried by FakeChatModel's prompt-
     * function dispatcher into a per-task output). Assertions:
     *   - Each taskId has audit rows in canonical order SUBMITTED→WORKING→COMPLETED
     *   - The agent has exactly 5 COMPLETED run rows
     *   - Every SSE body carries that task's own scripted output (no cross-leak)
     */
    @Test
    void fiveConcurrentTasks_eachIsolatedInAuditAndSseResponse() {
        int N = 5;
        String agentId = persistAgent("a2a-concurrent-" + UUID.randomUUID());

        // STICKY content-aware dispatcher: every call routes on its own prompt marker, so
        // the test is immune to chat-call-count variance under concurrency. A fixed-size
        // deque (one entry per task) would exhaust if any task made an extra LLM call,
        // dropping that task to FakeChatModel's generic "OK" fallback — which raced its
        // SSE response body to miss the per-task marker.
        fakeChatModel.respondWithDefault(p -> {
            String text = p.getInstructions().stream()
                    .map(m -> m.getText() != null ? m.getText() : "")
                    .reduce("", String::concat);
            // Encode the unique input marker back into the output so the test
            // can prove each SSE response carried the right per-task content.
            String marker = extractMarker(text);
            return new ChatResponse(java.util.List.of(new Generation(
                    new AssistantMessage("result-for-" + marker),
                    ChatGenerationMetadata.builder().finishReason("STOP").build())));
        });

        List<TaskCase> cases = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            cases.add(new TaskCase(
                    "task-conc-" + i + "-" + UUID.randomUUID(),
                    "marker-" + i + "-" + UUID.randomUUID()));
        }

        HttpHeaders auth = userHeaders("a2a-concurrent");

        List<CompletableFuture<ResponseEntity<String>>> futures = new ArrayList<>();
        for (TaskCase c : cases) {
            A2aTaskRequest req = new A2aTaskRequest(
                    c.taskId, agentId, "input " + c.marker,
                    null, "conc-session-" + c.marker, null, null);
            futures.add(CompletableFuture.supplyAsync(() ->
                    rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                            new HttpEntity<>(req, auth), String.class)));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS).join();

        // Wait until all N COMPLETED audit rows for the shared target agent are durable.
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE target_agent_id = ? AND status = 'COMPLETED'",
                        Integer.class, agentId) >= N);

        // agent_runs finalization is a SEPARATE async path (AgentRunFinalizer on its own
        // virtual thread) from the a2a_task_events audit above — its rows can lag the audit
        // rows. Poll for them rather than guessing with a fixed sleep, which flaked here.
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                        Integer.class, agentId) >= N);

        Integer agentCompletedRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                Integer.class, agentId);

        List<String> assertionFailures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            TaskCase c = cases.get(i);
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                    String.class, c.taskId);
            if (!statuses.equals(java.util.List.of("SUBMITTED", "WORKING", "COMPLETED"))) {
                assertionFailures.add("task " + c.taskId + " audit order = " + statuses);
            }
            String sseBody = futures.get(i).join().getBody();
            if (sseBody == null || !sseBody.contains("result-for-" + c.marker)) {
                assertionFailures.add("task " + c.taskId + " SSE body missing marker " + c.marker);
            }
        }

        assertAll("A2A concurrent task isolation — 5 tasks, no cross-contamination",
                () -> assertEquals(N, agentCompletedRuns.intValue(),
                        "agent_runs has exactly N=" + N + " COMPLETED rows for the shared agent — " +
                                "every concurrent task persisted its own run"),
                () -> assertTrue(assertionFailures.isEmpty(),
                        "per-task assertions all passed; failures: " + assertionFailures));
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    private record TaskCase(String taskId, String marker) {}

    /**
     * Extracts the unique per-task marker from a prompt's text. Each task sends
     * {@code "input marker-X"} as its input, which surfaces in the user message
     * passed to the LLM. The dispatcher uses this to produce a per-task response.
     */
    private static String extractMarker(String promptText) {
        int idx = promptText.indexOf("marker-");
        if (idx < 0) return "unknown";
        // marker-N-<UUID> — read until first whitespace
        int end = idx;
        while (end < promptText.length() && !Character.isWhitespace(promptText.charAt(end))) {
            end++;
        }
        return promptText.substring(idx, end);
    }

    private HttpHeaders userHeaders(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = registerLoginWithOrg(username, TenantConstants.DEFAULT_SYSTEM_ORG);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        return headers;
    }

    private String persistAgent(String id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName("A2A concurrent runtime target");
        a.setDescription("Concurrent task isolation fixture");
        a.setInstructions("Per-task content carries a unique marker.");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(false);
        a.setTeamMode(null);
        a.setMembers(null);
        return agentRepository.save(a).getId();
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }
}
