package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the FAILURE and CANCEL alternates of the A2A task lifecycle.
 *   {@link A2aSseLifecycleRuntimeTest} pins the happy path
 *   {@code SUBMITTED → WORKING → COMPLETED}; this class pins:
 *     1. Failure — agent throws → {@code SUBMITTED → WORKING → FAILED}, with the
 *        thrown exception's message captured into the {@code error_detail} column.
 *     2. Cancel mid-flight — agent enters {@code WORKING}, caller fires
 *        {@code DELETE /tasks/{id}}, executor cooperatively interrupts and audits
 *        {@code CANCELLED}. The first DELETE returns 204; a follow-up DELETE for the
 *        same id returns 404 because the task entry was removed from the executor's
 *        active maps after cancellation cleanup.
 *
 *   Together with the happy-path test, these three classes lock the three terminal
 *   states the executor's lifecycle is documented to produce.
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aLifecycleAlternatesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetState() {
        fakeModel.reset();
        seedModel("gpt-4o-mini");
        jdbc.update("TRUNCATE TABLE a2a_task_events");
    }

    @Test
    void failure_agentThrows_writesSUBMITTED_WORKING_FAILED_inOrder_withErrorDetail() {
        HttpHeaders auth = userHeaders("a2a-failure-path");
        String agentId = createAgent(auth, "failure-path target");

        // Script FakeChatModel to throw on the next call. The thrown message must reach
        // the FAILED audit row's error_detail column for operators to triage.
        String failureMessage = "simulated downstream failure " + UUID.randomUUID();
        fakeModel.respondWith((java.util.function.Function<Prompt, org.springframework.ai.chat.model.ChatResponse>) prompt -> {
            throw new RuntimeException(failureMessage);
        });

        String taskId = "task-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("targetAgentId", agentId);
        body.put("input", "exercise the failure branch");
        body.put("initiatingAgentId", "peer-init-failure");
        body.put("sessionId", "sess-" + UUID.randomUUID());

        // Read only the status — POST /tasks returns text/event-stream and the emitter completes
        // with an error on this failure path, which closes the chunked body abruptly; the outcome is
        // asserted via the a2a_task_events rows below, not the stream body.
        assertEquals(200, postForStatusNoBody("/api/v1/a2a/tasks", body, auth).value(),
                "POST /tasks still returns 200 — the SseEmitter is constructed even if the run will fail");

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED'",
                        Integer.class, taskId) > 0);

        List<Map<String, Object>> rowsInOrder = jdbc.queryForList(
                "SELECT status, error_detail FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                taskId);

        assertThat(rowsInOrder).as("failure path must emit at least SUBMITTED, WORKING, FAILED").hasSizeGreaterThanOrEqualTo(3);
        assertEquals(A2aTaskStatus.SUBMITTED.name(), rowsInOrder.get(0).get("status"));
        assertEquals(A2aTaskStatus.WORKING.name(), rowsInOrder.get(1).get("status"));
        Map<String, Object> last = rowsInOrder.get(rowsInOrder.size() - 1);
        assertEquals(A2aTaskStatus.FAILED.name(), last.get("status"),
                "terminal status for an agent-throw path must be FAILED");
        Object errorDetail = last.get("error_detail");
        assertThat(errorDetail).as("error_detail must carry the thrown exception message for operator triage").isNotNull();
        assertThat(errorDetail.toString()).contains(failureMessage);
    }

    @Test
    void cancel_midFlight_audits_CANCELLED_andSecondCancelReturns404() throws InterruptedException {
        HttpHeaders auth = userHeaders("a2a-cancel-midflight");
        String agentId = createAgent(auth, "cancel-midflight target");

        // Deterministic coordination: the lambda counts down `modelInvoked` so the test
        // knows the executor's virtual thread is parked inside `agentOperations.run()`
        // (past the advisor chain, inside the model call) — the cancel race window is
        // open and stable. The lambda then awaits `cancelArrived` indefinitely; the only
        // way out is the interrupt issued by A2ATaskExecutor.cancelTask via
        // activeTaskThreads.get(taskId).interrupt(). That throws InterruptedException
        // from await(), the lambda rethrows, and the executor's catch-all routes to
        // the CANCELLED audit branch. No Thread.sleep — no early-return race.
        final CountDownLatch modelInvoked = new CountDownLatch(1);
        final CountDownLatch cancelArrived = new CountDownLatch(1);
        fakeModel.respondWith((java.util.function.Function<Prompt, org.springframework.ai.chat.model.ChatResponse>) prompt -> {
            modelInvoked.countDown();
            try {
                cancelArrived.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
            }
            throw new IllegalStateException("test bug: latch released without interrupt — cancelTask path bypassed");
        });

        String taskId = "task-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("targetAgentId", agentId);
        body.put("input", "task that will be cancelled mid-flight");
        body.put("initiatingAgentId", "peer-init-cancel");
        body.put("sessionId", "sess-" + UUID.randomUUID());

        // POST holds the SSE stream open until the run terminates — it can't run on the
        // test thread or we'd block waiting on the model lambda that is itself waiting
        // on the cancel-from-the-test-thread. Run on a virtual thread; the stream closes
        // (and this thread exits) once the executor processes the cancel and SseAgentEmitter
        // calls sseEmitter.complete().
        Thread postThread = Thread.ofVirtual().name("a2a-cancel-midflight-post").start(() -> {
            try {
                rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                        new HttpEntity<>(body, auth), String.class);
            } catch (Exception ignored) {
                // SSE close after cancel may surface as a parse exception on the client;
                // the test does not assert on this thread's outcome.
            }
        });

        // The virtual thread must reach the model call. modelInvoked.countDown happens
        // after WORKING is audited and after activeTaskThreads.put — so by the time we
        // proceed, both preconditions for cancelTask's fallback interrupt path hold.
        assertTrue(modelInvoked.await(15, TimeUnit.SECONDS),
                "FakeChatModel lambda must be invoked within 15s — proves the executor "
                        + "passed activeTaskThreads.put and the advisor chain reached the model call");

        // Belt-and-suspenders: WORKING audit must already be in the DB (executor writes
        // it before the run call). If this fails after modelInvoked countDown, the
        // executor's audit ordering regressed.
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'WORKING'",
                        Integer.class, taskId) > 0);

        // First cancel: must return 204 because the task is in flight.
        ResponseEntity<Void> firstCancel = rest.exchange(
                url("/api/v1/a2a/tasks/" + taskId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(204, firstCancel.getStatusCode().value(),
                "first cancel against an in-flight task must 204 — cancel signal was accepted");

        // Await the CANCELLED audit row produced by the executor after cooperative interrupt.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'CANCELLED'",
                        Integer.class, taskId) > 0);

        // Second cancel after cleanup: the task entry is removed from activeTaskRuns and
        // activeTaskThreads, so the executor returns false and the controller maps to 404.
        ResponseEntity<Void> secondCancel = rest.exchange(
                url("/api/v1/a2a/tasks/" + taskId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(404, secondCancel.getStatusCode().value(),
                "follow-up cancel after the task already CANCELLED must 404 — task is gone from the executor's maps");
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "lifecycle-alternates fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201");
        return agentId;
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-a2a-lifecycle-alt", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
