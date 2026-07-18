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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Inbound A2A task lifecycle runtime coverage. Drives the full
 *   {@code POST /api/v1/a2a/tasks} → {@code A2ATaskExecutor#submitTask} →
 *   {@code AgentOperations#run} path against a real Spring context (Postgres via
 *   {@link BaseIntegrationTest}) and asserts on the durable audit trail in
 *   {@code a2a_task_events}.
 *
 *   This sits alongside {@code A2aMeshRuntimeTest} (peer registry), {@code
 *   OutboundApiKeyRotationRuntimeTest} (key rotation), and {@code
 *   PeerCancellationNotifyRuntimeTest} (cross-peer cancel). Those exercise A2A's
 *   federation and security plane; this one closes the gap on Gap 2.2 "Task Sockets"
 *   — the actual execution lifecycle that drives an external peer's task through to
 *   completion.
 *
 *   Real wiring exercised end-to-end:
 *   - Spring Security (JWT filter chain via real {@code /api/auth/register} + login)
 *   - {@code A2AController.submitTask} → effective-request UUID assignment
 *   - {@code A2ATaskExecutor.submitTask} (sync) and {@code .executeTask} (virtual thread)
 *   - {@code SseAgentEmitter.submit/startWork/complete} — SSE events emitted
 *   - {@code A2ATaskExecutor.audit()} — async virtual-thread JPA insert into
 *     {@code a2a_task_events}
 *   - {@code AgentOperations#run} (production {@code AgentService}) wiring through the
 *     {@code FakeChatModel} seam so we get a deterministic LLM response without
 *     touching OpenAI / Anthropic / Google
 *
 *   FakeChatModel is the LLM seam (not mocked); everything between the HTTP layer and
 *   the model call is production code.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aInboundTaskRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Happy-path lifecycle. POST a valid A2aTaskRequest, wait for COMPLETED
     * to land in {@code a2a_task_events}, then pin every cross-layer invariant the
     * real execution path is supposed to honor:
     *   - HTTP 200 with SSE response
     *   - Audit trail order: SUBMITTED → WORKING → COMPLETED (exactly 3 rows)
     *   - {@code agent_runs} has one COMPLETED row for the target agent (real
     *     AgentService dispatched, not a stub)
     *   - FakeChatModel saw exactly one prompt carrying the inbound task input
     *   - COMPLETED audit message carries the LLM's content
     */
    @Test
    void submitTask_happyPath_emitsLifecycleAndPersistsAgentRun() {
        String agentId = persistAgent("a2a-target-" + UUID.randomUUID());
        fakeChatModel.respondWith("a2a inbound task result");

        String taskId = "task-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-happy");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "inbound A2A test input",
                "remote-peer-agent", "a2a-session-" + UUID.randomUUID(),
                null, null);

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/a2a/tasks"),
                HttpMethod.POST,
                new HttpEntity<>(request, auth),
                String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(250, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        Integer completedRunRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                Integer.class, agentId);
        List<Prompt> prompts = fakeChatModel.receivedPrompts();
        String sseBody = response.getBody();

        assertAll("A2A inbound task — happy-path lifecycle",
                () -> assertEquals(HttpStatus.OK, response.getStatusCode(),
                        "controller returns 200 — SSE response body served"),
                () -> assertEquals(List.of("SUBMITTED", "WORKING", "COMPLETED"), statuses,
                        "audit trail order: SUBMITTED → WORKING → COMPLETED, no extra rows"),
                () -> assertEquals(1, completedRunRows,
                        "agent_runs has exactly one COMPLETED row for the target agent — " +
                                "real AgentService dispatched"),
                () -> assertEquals(1, prompts.size(),
                        "FakeChatModel saw exactly one prompt — A2A executor called " +
                                "AgentOperations.run once"),
                () -> assertTrue(promptUserText(prompts.get(0)).contains("inbound A2A test input"),
                        "the prompt to the LLM carries the A2A request input — the request " +
                                "input traversed the full HTTP → executor → AgentService → " +
                                "ChatClient seam"),
                () -> assertNotNull(sseBody,
                        "SSE response body is non-null — Spring MVC flushed the emitter"),
                () -> assertTrue(sseBody.contains("\"status\":\"SUBMITTED\""),
                        "SSE stream carried the SUBMITTED event JSON"),
                () -> assertTrue(sseBody.contains("\"status\":\"WORKING\""),
                        "SSE stream carried the WORKING event JSON"),
                () -> assertTrue(sseBody.contains("\"status\":\"COMPLETED\""),
                        "SSE stream carried the COMPLETED event JSON"),
                () -> assertTrue(sseBody.contains("a2a inbound task result"),
                        "SSE stream's COMPLETED event carries the LLM output — round-trip from " +
                                "FakeChatModel through SseAgentEmitter.complete to the HTTP body"));
    }

    /**
     * Case 2 — Null taskId in the request body. Controller assigns a UUID and that
     * UUID surfaces on every audit row, proving the assigned identifier carries
     * through the full pipeline.
     */
    @Test
    void submitTask_withNullTaskId_controllerAssignsUuidAndAuditsAreReachable() {
        String agentId = persistAgent("a2a-target-auto-" + UUID.randomUUID());
        fakeChatModel.respondWith("auto-taskid result");

        HttpHeaders auth = userHeaders("a2a-auto");
        A2aTaskRequest request = new A2aTaskRequest(
                null, agentId, "auto-taskid input",
                "remote-peer", "auto-session", null, null);

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/a2a/tasks"),
                HttpMethod.POST,
                new HttpEntity<>(request, auth),
                String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(250, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE target_agent_id = ? AND status = 'COMPLETED'",
                        Integer.class, agentId) >= 1);

        String assignedTaskId = jdbc.queryForObject(
                "SELECT task_id FROM a2a_task_events WHERE target_agent_id = ? AND status = 'COMPLETED' " +
                        "ORDER BY event_ts DESC LIMIT 1",
                String.class, agentId);
        Integer rowsForAssignedTaskId = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?",
                Integer.class, assignedTaskId);

        assertAll("A2A null-taskId — controller mints UUID and it surfaces on every audit row",
                () -> assertEquals(HttpStatus.OK, response.getStatusCode(),
                        "null taskId is accepted by the controller (record allows null)"),
                () -> assertNotNull(assignedTaskId,
                        "controller-assigned taskId persists to a2a_task_events"),
                () -> assertDoesNotThrow(() -> UUID.fromString(assignedTaskId),
                        "assigned taskId is a valid UUID per the controller's convention"),
                () -> assertEquals(3, rowsForAssignedTaskId,
                        "all three lifecycle rows (SUBMITTED + WORKING + COMPLETED) share the " +
                                "controller-assigned taskId — identifier propagation through the full path"));
    }

    /**
     * Case 3 — COMPLETED is terminal: no further audit rows arrive after the
     * stream-closing event. Guards against regressions where a future advisor or
     * post-completion hook accidentally writes another row.
     *
     * The 1s settle wait after COMPLETED is deliberate: audit rows are written on
     * a separate virtual thread by {@link ai.operativus.agentmanager.control.a2a.A2ATaskExecutor#audit}.
     * A regression that fired an extra event after completion would race in within
     * that window. 1s gives enough headroom for the JPA insert thread to flush
     * without making the test slow.
     */
    @Test
    void submitTask_completedIsTerminal_noRowsAfter() {
        String agentId = persistAgent("a2a-target-term-" + UUID.randomUUID());
        fakeChatModel.respondWith("terminal result");

        String taskId = "task-term-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-term");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "terminal input",
                null, "term-session", null, null);

        rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                new HttpEntity<>(request, auth), String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(250, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);

        sleepQuietly(1000);

        Integer totalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?",
                Integer.class, taskId);
        String lastStatus = jdbc.queryForObject(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts DESC, id DESC LIMIT 1",
                String.class, taskId);

        assertAll("A2A COMPLETED is terminal — no rows arrive after",
                () -> assertEquals(3, totalRows,
                        "exactly 3 rows: SUBMITTED + WORKING + COMPLETED — no spurious rows after terminal"),
                () -> assertEquals("COMPLETED", lastStatus,
                        "COMPLETED is the last audited status — terminal closes the stream and nothing else writes"));
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

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
        a.setName("A2A inbound runtime target");
        a.setDescription("A2A inbound runtime fixture — drives FakeChatModel via real AgentService");
        a.setInstructions("Echo the user input back through the model.");
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

    private static String promptUserText(Prompt p) {
        StringBuilder sb = new StringBuilder();
        for (Message m : p.getInstructions()) {
            sb.append(m.getText()).append('\n');
        }
        return sb.toString();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
