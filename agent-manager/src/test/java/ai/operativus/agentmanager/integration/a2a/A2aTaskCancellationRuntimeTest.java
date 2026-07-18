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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Mid-flight A2A task cancellation runtime coverage. Drives the
 *   {@code DELETE /api/v1/a2a/tasks/{taskId}} path against a real Spring context while
 *   a sibling task is blocked inside {@code AgentOperations.run}, exercising the
 *   virtual-thread interrupt path in {@code A2ATaskExecutor.executeTask}.
 *
 *   The cancellation path under test:
 *   <pre>
 *   POST /tasks (SUBMITTED + WORKING audit)
 *     -> A2ATaskExecutor.executeTask spawns virtual thread
 *     -> agentOperations.run(...) blocks inside FakeChatModel.sleep
 *     -> activeTaskThreads has taskId, activeTaskRuns does NOT (runId not yet returned)
 *   DELETE /tasks/{taskId}
 *     -> cancelTask(taskId) -> Thread.interrupt() on the virtual thread
 *     -> sleep throws InterruptedException -> wrapped as RuntimeException
 *     -> executor catch block: e.getCause() instanceof InterruptedException
 *     -> emitter.cancel(...) + audit(CANCELLED) + peerCancellationDispatcher.notifyCancellation
 *   </pre>
 *
 *   This complements {@code PeerCancellationNotifyRuntimeTest} (which covers the
 *   dispatcher's outbound HTTP behavior and the inbound {@code /cancel-notify} endpoint)
 *   by proving the *initiator* side — that a DELETE during WORKING actually interrupts
 *   the blocking run, audits CANCELLED, and triggers the cross-peer notify call.
 *
 *   {@code initiatingAgentId} is intentionally set to a non-registered alias so the
 *   {@code PeerCancellationDispatcher} short-circuits with a "no peer found" no-op
 *   (proved by {@code PeerCancellationNotifyRuntimeTest.notifyForUnknownInitiator_isNoOp}).
 *   That keeps this test focused on the executor's interrupt + audit semantics without
 *   needing a MockRestServiceServer for the peer.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aTaskCancellationRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Mid-flight cancel: DELETE arrives while the executor's virtual thread
     * is blocked inside the LLM call. The interrupt propagates into FakeChatModel's
     * sleep, the executor catches it via the {@code InterruptedException}-cause branch,
     * and emits/audits CANCELLED. DELETE returns 204.
     */
    @Test
    void cancelDuringWorking_interruptsExecutorAndAuditsCancelled() {
        String agentId = persistAgent("a2a-target-cancel-" + UUID.randomUUID());

        // Long sleep that propagates interrupt as a RuntimeException with InterruptedException
        // cause — the executor catches this and routes to the CANCELLED branch (executor:204).
        fakeChatModel.respondWith(p -> {
            try {
                Thread.sleep(15_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("unreachable — should have been cancelled"),
                    ChatGenerationMetadata.builder().finishReason("STOP").build())));
        });

        String taskId = "task-cancel-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-cancel");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "long-running input",
                "unregistered-peer-" + UUID.randomUUID(),   // peer notify is a graceful no-op
                "cancel-session-" + UUID.randomUUID(), null, null);

        // Submit asynchronously — POST blocks until the SSE emitter completes
        // (which happens when our DELETE causes emitter.cancel -> sseEmitter.complete).
        CompletableFuture<ResponseEntity<String>> submitFuture = CompletableFuture.supplyAsync(() ->
                rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                        new HttpEntity<>(request, auth), String.class));

        // Wait until WORKING is audited — guarantees the executor reached the blocking
        // run() call and registered the virtual thread in activeTaskThreads.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'WORKING'",
                        Integer.class, taskId) >= 1);

        // Issue the cancel
        ResponseEntity<Void> deleteResponse = rest.exchange(
                url("/api/v1/a2a/tasks/" + taskId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        // Wait for CANCELLED audit
        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'CANCELLED'",
                        Integer.class, taskId) >= 1);

        // The original submit POST should now complete (the SSE emitter was closed by cancel)
        ResponseEntity<String> submitResponse = submitFuture.orTimeout(15, TimeUnit.SECONDS).join();

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        Integer completedRunRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                Integer.class, agentId);
        String cancelledMessage = jdbc.queryForObject(
                "SELECT message FROM a2a_task_events WHERE task_id = ? AND status = 'CANCELLED' " +
                        "ORDER BY event_ts DESC LIMIT 1",
                String.class, taskId);

        assertAll("A2A mid-flight cancellation — DELETE interrupts blocking run",
                () -> assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode(),
                        "DELETE returns 204 — A2AController.cancelTask saw the task as active"),
                () -> assertTrue(statuses.contains("SUBMITTED"),
                        "SUBMITTED was audited before the cancel arrived"),
                () -> assertTrue(statuses.contains("WORKING"),
                        "WORKING was audited before the cancel arrived"),
                () -> assertTrue(statuses.contains("CANCELLED"),
                        "CANCELLED was audited after the interrupt — executor caught " +
                                "InterruptedException via the cause-chain branch"),
                () -> assertEquals("CANCELLED",
                        statuses.get(statuses.size() - 1),
                        "CANCELLED is the last audited status — terminal"),
                () -> assertEquals(0, completedRunRows,
                        "agent_runs has NO COMPLETED row — the run was interrupted before " +
                                "AgentService could persist a completed run"),
                () -> assertEquals(HttpStatus.OK, submitResponse.getStatusCode(),
                        "original POST returns 200 — SseAgentEmitter.cancel uses sseEmitter.complete() " +
                                "(graceful close), so the response status was already 200 when the stream began"),
                () -> assertTrue(submitResponse.getBody() != null
                                && submitResponse.getBody().contains("\"status\":\"CANCELLED\""),
                        "SSE response body carries the CANCELLED event — the client saw the " +
                                "lifecycle transition over the stream before close"),
                () -> assertTrue(cancelledMessage != null && cancelledMessage.contains("Cancelled"),
                        "CANCELLED audit message tags the cancellation reason"));
    }

    /**
     * Case 2 — DELETE on a taskId that was never submitted: cancelTask returns false,
     * controller surfaces 404. No audit rows written.
     */
    @Test
    void cancelUnknownTaskId_returns404AndAuditsNothing() {
        String unknownTaskId = "task-unknown-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-cancel-unknown");

        ResponseEntity<Void> deleteResponse = rest.exchange(
                url("/api/v1/a2a/tasks/" + unknownTaskId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?",
                Integer.class, unknownTaskId);

        assertAll("A2A cancel unknown taskId — 404 + no audit",
                () -> assertEquals(HttpStatus.NOT_FOUND, deleteResponse.getStatusCode(),
                        "DELETE returns 404 — cancelTask returned false (taskId not tracked)"),
                () -> assertEquals(0, auditRows,
                        "no audit rows written — cancel of an unknown task is a no-op"));
    }

    /**
     * Case 3 — Cancel after the task already completed: cancelTask returns false
     * because the executor's {@code finally} block removed the entry from
     * {@code activeTaskRuns} + {@code activeTaskThreads}. Controller surfaces 404.
     * The original COMPLETED audit row persists; no CANCELLED row is added.
     */
    @Test
    void cancelAfterCompleted_returns404AndDoesNotAuditCancelled() {
        String agentId = persistAgent("a2a-target-post-" + UUID.randomUUID());
        fakeChatModel.respondWith("fast result");

        String taskId = "task-post-complete-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-cancel-post");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "fast input",
                null, "post-session", null, null);

        rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                new HttpEntity<>(request, auth), String.class);

        // Wait until COMPLETED lands, plus 1s settle to ensure the executor's
        // finally block cleaned up activeTaskRuns + activeTaskThreads.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);
        sleepQuietly(500);

        ResponseEntity<Void> deleteResponse = rest.exchange(
                url("/api/v1/a2a/tasks/" + taskId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        sleepQuietly(500);   // give async audit thread a chance if a regression wrote one

        Integer cancelledRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'CANCELLED'",
                Integer.class, taskId);
        Integer completedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                Integer.class, taskId);

        assertAll("A2A cancel after COMPLETED — 404, no CANCELLED audit",
                () -> assertEquals(HttpStatus.NOT_FOUND, deleteResponse.getStatusCode(),
                        "DELETE returns 404 — executor's finally cleared the activeTask tracking"),
                () -> assertEquals(0, cancelledRows,
                        "no CANCELLED row added — the task was already terminal"),
                () -> assertEquals(1, completedRows,
                        "original COMPLETED row remains untouched"));
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
        a.setName("A2A cancel runtime target");
        a.setDescription("A2A cancellation runtime fixture");
        a.setInstructions("Cancellation target — FakeChatModel may sleep here.");
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

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
