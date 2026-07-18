package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Black-box runtime coverage of the background-run surface —
 *   {@code POST /api/agents/{agentId}/runs/background}, {@code GET /runs/{runId}/status},
 *   {@code DELETE /runs/{runId}} — driven by
 *   {@link com.operativus.agentmanager.compute.service.AgentService#runInBackground}
 *   → {@link com.operativus.agentmanager.compute.service.RunExecutionManager#submit}.
 *   Pins the full QUEUED → RUNNING → COMPLETED/CANCELLED lifecycle and the
 *   {@code runExecutionManager.cancel} override-to-CANCELLED semantics.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §6.7 (background runs).
 *
 * Implementation notes / gaps these tests pin:
 *   - Background runs do NOT use {@link com.operativus.agentmanager.control.service.PersistentJobQueueService};
 *     they go straight through {@link RunExecutionManager#submit} which schedules the run
 *     on a fresh {@code Executors.newVirtualThreadPerTaskExecutor()} and returns the runId
 *     synchronously. The scheduler is therefore NOT on the critical path — no
 *     {@code SchedulerTestSupport.tickJobQueue()} call is needed here. A regression that
 *     accidentally re-routes background runs through the persistent queue would surface
 *     here as the run staying QUEUED forever (no handler picked it up).
 *   - {@link com.operativus.agentmanager.control.controller.AgentsController#runBackground}
 *     hardcodes the response {@code status} string to {@code "QUEUED"} regardless of the
 *     actual persisted row state at return time (the virtual thread may have already
 *     transitioned to RUNNING/COMPLETED before the HTTP response is flushed). Tests must
 *     therefore assert on the {@link AgentRunStatusDTO} shape (runId + literal "QUEUED"),
 *     then poll the DB for eventual-state assertions.
 *   - {@link AgentRun#AgentRun(String, String, String, String, String)} defaults
 *     {@code status = RunStatus.QUEUED} and {@link AgentService#runInBackground} saves the
 *     row BEFORE calling {@code submit}, so an {@code agent_runs} row with the returned
 *     id is guaranteed to exist from the moment the HTTP response lands.
 *   - Cancellation path ({@link RunExecutionManager#cancel}) has two branches:
 *     (a) when the future is still in {@code activeRuns}, it calls {@code future.cancel(true)}
 *         and UNCONDITIONALLY sets status=CANCELLED (this overrides a just-completed
 *         run — a known race worth pinning, but NOT exercised here; interrupting a
 *         virtual thread mid-advisor-JDBC reliably zombies the PG connection and deadlocks
 *         the next test's TRUNCATE, so branch (a) is deferred until the connection pool can
 *         reclaim interrupted sockets); (b) when the future is gone from {@code activeRuns}
 *         (virtual thread's finally-block popped it), it only transitions non-terminal
 *         statuses. Test (c) exercises branch (b) against an already-COMPLETED run to pin
 *         the terminal-status guard and the 204 response even on no-op cancels.
 *   - Reflection is suppressed via {@link NoOpReflectionServiceConfig} for the same reason
 *     as sync/stream tests: {@link com.operativus.agentmanager.compute.service.ReflectionService#reflectOnRun}
 *     is {@code @Async} and would fire an extra ChatModel call from a different thread,
 *     consuming FakeChatModel script entries out of order and polluting prompt counts.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class BackgroundRunsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // Background runs finalize via a virtual thread — polling is the correct pattern.
    // 10s is comfortably past a warm JVM's typical 100-500ms completion while still
    // failing fast if a regression wedges the thread entirely.
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private FakeChatModel fakeModel;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeModel.reset();
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    // §6.7 — Case (a): POST /runs/background returns a non-null runId and the literal
    // "QUEUED" status string. The agent_runs row is persisted synchronously in
    // AgentService.runInBackground BEFORE the virtual thread submits, so a row with the
    // returned id is guaranteed to exist — even if the row has already transitioned off
    // QUEUED by the time we read it (race with the virtual thread). Pin on response
    // shape + row existence; terminal-state assertions belong in case (b).
    @Test
    void backgroundRunReturnsQueuedStatusAndPersistsAgentRunsRowWithReturnedId() {
        HttpHeaders auth = authenticatedHeaders("bg-enqueue-runner");
        String agentId = createAgentViaApi(auth, "Background Enqueue Agent");

        fakeModel.respondWith("Background reply.");

        String sessionId = "session-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "message", "Run me in the background.",
                "sessionId", sessionId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/agents/" + agentId + "/runs/background"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "runBackground is a blocking POST returning immediately — 200 OK, not 202 Accepted");
        Map<String, Object> payload = response.getBody();
        assertNotNull(payload, "AgentRunStatusDTO body must be populated on success");
        String runId = (String) payload.get("runId");
        assertNotNull(runId, "AgentRunStatusDTO.runId must be the tracking id — otherwise clients cannot poll /status");
        assertEquals("QUEUED", payload.get("status"),
                "AgentRunStatusDTO.status is hardcoded to 'QUEUED' in AgentsController.runBackground regardless of the virtual thread's actual DB state — pin prevents a refactor from silently returning RUNNING/COMPLETED and confusing polling clients");

        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE id = ? AND agent_id = ?",
                Long.class, runId, agentId);
        assertEquals(1L, rowCount,
                "agent_runs row must be persisted synchronously before return (AgentService.runInBackground:363) so status polling never 404s immediately after enqueue");
    }

    // §6.7 — Case (b): the queued run actually fires on the virtual thread, transitions
    // to COMPLETED, and persists the assistant reply as agent_runs.output. Also asserts
    // the GET /status endpoint returns the same terminal row. Pins both the RunExecutionManager
    // submit path AND the status-poll contract in one test so a regression in either leg
    // fails loudly.
    @Test
    void backgroundRunFiresOnVirtualThreadAndTransitionsToCompletedWithPersistedOutput() {
        HttpHeaders auth = authenticatedHeaders("bg-complete-runner");
        String agentId = createAgentViaApi(auth, "Background Complete Agent");

        String cannedReply = "Background virtual-thread completed reply.";
        fakeModel.respondWith(cannedReply);

        String sessionId = "session-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "message", "Please complete the background run.",
                "sessionId", sessionId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/agents/" + agentId + "/runs/background"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String runId = (String) response.getBody().get("runId");

        // The virtual thread transitions QUEUED → RUNNING → COMPLETED. Poll on the DB
        // for terminal state — no @Scheduled tick is involved, the thread is already
        // running by the time we got the HTTP response (usually).
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status, output FROM agent_runs WHERE id = ?", runId);
            assertEquals("COMPLETED", runRow.get("status"),
                    "background virtual thread must reach COMPLETED; if it wedges at QUEUED the submit path is broken, if FAILED the sync run path itself is broken");
            assertEquals(cannedReply, runRow.get("output"),
                    "RunExecutionManager.submit writes response.content() to run.output — must echo the FakeChatModel scripted reply verbatim");
        });

        // Status-poll endpoint contract: AgentRunResponse JSON, 200 OK. PR #501 removed
        // the singular GET /api/agents/{agentId}/runs/{runId}/status handler in favor of
        // the user-side GET /api/v1/runs/{runId} (tenant + ownership scoped). Same
        // underlying RunOperations.findById; different DTO shape (AgentRunResponse
        // exposes id, status, and metric columns).
        ResponseEntity<Map<String, Object>> statusResponse = rest.exchange(
                url("/api/v1/runs/" + runId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, statusResponse.getStatusCode(),
                "GET /api/v1/runs/{id} must return 200 for an existing runId owned by the caller — 404 indicates RunOperations.findById drift OR callerMayReadRun tenant/ownership filter rejected the row");
        Map<String, Object> statusBody = statusResponse.getBody();
        assertNotNull(statusBody);
        assertEquals("COMPLETED", statusBody.get("status"),
                "status field must match the row we just polled via JDBC");
        assertEquals(runId, statusBody.get("id"));
    }

    // §6.7 — Case (c): DELETE /runs/{id} on an ALREADY-COMPLETED background run returns
    // 204 and leaves the row's terminal status (COMPLETED) intact. This exercises the
    // else-branch of RunExecutionManager.cancel — the future has already been removed from
    // activeRuns by the virtual thread's finally-block, so the guard
    // {@code run.getStatus() != COMPLETED && run.getStatus() != FAILED} protects the terminal
    // status from being overwritten to CANCELLED.
    //
    // Why not cancel mid-run? An earlier draft gated the FakeChatModel behind a latch so we
    // could interrupt the virtual thread mid-advisor-chain. That reliably deadlocked the
    // NEXT test's truncate — future.cancel(true) during a JDBC call (PII policy lookup)
    // leaves the PG connection in a zombie "closed by interrupt" state, and the dangling
    // transaction holds row locks that Postgres surfaces as "deadlock detected" on the next
    // TRUNCATE. The "cancel CANCELLED branch (a)" path is worth pinning, but cannot be done
    // safely via a real HTTP/DB round-trip without draining the connection pool between tests —
    // deferred until the pool has a way to reclaim interrupted connections.
    @Test
    void deleteBackgroundRunOnCompletedRunReturns204AndPreservesTerminalStatus() {
        HttpHeaders auth = authenticatedHeaders("bg-cancel-runner");
        String agentId = createAgentViaApi(auth, "Background Cancel-After-Complete Agent");

        fakeModel.respondWith("Completed before cancel.");

        String sessionId = "session-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "message", "Complete quickly.",
                "sessionId", sessionId);

        ResponseEntity<Map<String, Object>> enqueueResponse = rest.exchange(
                url("/api/agents/" + agentId + "/runs/background"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, enqueueResponse.getStatusCode());
        String runId = (String) enqueueResponse.getBody().get("runId");

        // Let the virtual thread finish and the finally-block pop the future out of activeRuns —
        // if we DELETE before that, we'd hit the OTHER branch (future-present → unconditional
        // CANCELLED), which is the race we're explicitly NOT pinning here.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
            assertEquals("COMPLETED", status,
                    "precondition: run must reach COMPLETED (and the finally-block must have removed the future from activeRuns) before we DELETE");
        });

        ResponseEntity<Void> cancelResponse = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, cancelResponse.getStatusCode(),
                "cancelRun returns 204 No Content even on no-op terminal-state cancel — pins the contract for UI clients that fire DELETE without checking status first");

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
        assertEquals("COMPLETED", finalStatus,
                "terminal-status guard in RunExecutionManager.cancel must prevent CANCELLED from overwriting COMPLETED — otherwise every late client DELETE would rewrite history");
    }

    // ─── helpers ───

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Background run test owner");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        // Same memoryEnabled gotcha pinned in SyncRunsRuntimeTest — though this suite
        // does not assert on agent_messages rows, we keep the field explicit so an agent
        // fixture change in one run-suite stays consistent with the others.
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before background run endpoints reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-bg-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
