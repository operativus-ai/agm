package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the SSE disconnect-resilience contract. A peer that
 *   submits an A2A task and disconnects (network drop, timeout, client kill) before
 *   the task completes must not affect server-side execution — the executor's virtual
 *   thread runs independently of the SSE sink, and the audit trail must be complete
 *   regardless of whether the SSE events ever reached the peer.
 *
 *   {@code SseAgentEmitter.send} swallows {@code IOException} on a closed sink
 *   (logs at debug; does not throw). The executor's thread continues through
 *   {@code agentOperations.run} → completion → audit. Production reliability
 *   contract: lost-connection ≠ lost-audit, lost-connection ≠ lost-run.
 *
 *   This test uses a separate {@code RestTemplate} with a short read timeout
 *   (~1s) and a {@code FakeChatModel} that sleeps ~3s before responding. The
 *   client times out and throws {@code ResourceAccessException} before the
 *   server-side virtual thread finishes the run. Awaitility then polls the
 *   audit table and {@code agent_runs} to prove the task completed.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aSseDisconnectRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Client disconnects (read timeout) before the task completes. The
     * server-side virtual thread continues through to COMPLETED. Audit trail and
     * agent_runs row persist as if the client had stayed connected.
     */
    @Test
    void clientDisconnectMidStream_taskStillCompletes_andAuditTrailIsComplete() {
        String agentId = persistAgent("a2a-sse-drop-" + UUID.randomUUID());

        // FakeChatModel sleeps long enough that the client's 1s read timeout
        // fires before the server finishes the run.
        fakeChatModel.respondWith(p -> {
            try {
                Thread.sleep(3_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("delayed result — peer should never see this over SSE"),
                    ChatGenerationMetadata.builder().finishReason("STOP").build())));
        });

        // Custom RestTemplate with a short read timeout — this triggers a
        // client-side read timeout while the server is still in agentOperations.run.
        RestTemplate disconnectingClient = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(1_000);
        disconnectingClient.setRequestFactory(factory);

        String taskId = "task-disconnect-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-disconnect");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "long-running input",
                null, "disconnect-session", null, null);

        // The POST will fail client-side (read timeout or transport-level disconnect);
        // the server keeps executing. We catch RestClientException (the parent of
        // ResourceAccessException) because the exact subclass depends on whether the
        // failure surfaces as IO timeout, partial-read parse failure, or unknown content.
        RestClientException timeoutEx = assertThrows(RestClientException.class, () ->
                disconnectingClient.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                        new HttpEntity<>(request, auth), String.class),
                "client must throw a RestClientException — disconnect / read timeout simulated");

        // Wait for the server-side virtual thread to complete the run and audit
        // a COMPLETED row even though the SSE peer is long gone.
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(250, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        Integer completedAgentRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                Integer.class, agentId);

        assertAll("A2A SSE disconnect resilience — task completes server-side after client drop",
                () -> assertTrue(timeoutEx.getMessage() != null && !timeoutEx.getMessage().isBlank(),
                        "client-side timeout exception is non-empty (sanity check)"),
                () -> assertEquals(List.of("SUBMITTED", "WORKING", "COMPLETED"), statuses,
                        "audit trail is complete (SUBMITTED+WORKING+COMPLETED) even though " +
                                "the SSE peer disconnected before the server emitted the events — " +
                                "proves SseAgentEmitter.send's IOException-swallow doesn't break " +
                                "the audit path"),
                () -> assertEquals(1, completedAgentRuns,
                        "agent_runs has the COMPLETED row — the run was finalized server-side " +
                                "after the peer was already gone"),
                () -> assertEquals(1, fakeChatModel.receivedPrompts().size(),
                        "FakeChatModel was invoked exactly once — the executor's virtual thread " +
                                "did not bail when the SSE sink closed"));
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
        a.setName("A2A SSE-disconnect runtime target");
        a.setDescription("SSE-disconnect resilience fixture");
        a.setInstructions("Slow target — FakeChatModel sleeps before responding.");
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
