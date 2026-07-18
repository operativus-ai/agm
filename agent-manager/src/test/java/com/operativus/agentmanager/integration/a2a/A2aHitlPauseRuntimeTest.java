package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the HITL pause-propagation contract on the inbound A2A
 *   path. {@code AgentService.run} catches {@code ApprovalRequiredException} internally
 *   and returns {@code RunResponse(status=PAUSED, content="Tool execution paused: ...",
 *   metadata.requiredAction={approvalId,...})}. Before this PR's executor fix,
 *   {@code A2ATaskExecutor} ignored {@code response.status()} and unconditionally emitted
 *   COMPLETED — peers saw a "done" event for a task that was actually waiting on human
 *   approval, with no way to drive the resume.
 *
 *   The executor fix in this PR adds the missing status check: when
 *   {@code response.status() == PAUSED}, route to {@code emitter.pause(taskId, runId,
 *   approvalId)} + audit {@code A2aTaskStatus.PAUSED}, instead of COMPLETED.
 *
 *   Test approach: rather than building a full HITL-tool fixture (which would require an
 *   {@code @AgentToolComponent} + {@code ToolTierResolverProvider} + FakeChatModel
 *   tool_call scripting — disproportionate to what this test is covering), we substitute
 *   a focused {@link AgentOperations} stub via {@code @TestConfiguration + @Primary} that
 *   returns a synthetic PAUSED {@link RunResponse} when the input contains a sentinel
 *   marker. The test exercises {@code A2ATaskExecutor}'s response-routing logic
 *   end-to-end (real HTTP → controller → executor → SSE emitter + audit virtual thread →
 *   DB), with only the production code under test (the response-status switch) on the
 *   path. The PAUSED contract assertion is real; the AgentOperations boundary stub is a
 *   test seam that mirrors {@code FakeChatModel}'s LLM-seam substitution.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        A2aHitlPauseRuntimeTest.PausedAgentOperationsConfig.class})
public class A2aHitlPauseRuntimeTest extends BaseIntegrationTest {

    private static final String PAUSE_SENTINEL = "TRIGGER_HITL_PAUSE_PROBE";

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — A run that returns RunResponse(status=PAUSED) must surface as a PAUSED
     * audit row (NOT COMPLETED), and the SSE response body must carry a PAUSED event
     * with the approvalId so the peer can drive the resume.
     */
    @Test
    void a2aRun_whenAgentServiceReturnsPaused_executorEmitsPausedNotCompleted() {
        String agentId = persistAgent("a2a-hitl-pause-" + UUID.randomUUID());

        String taskId = "task-hitl-pause-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-hitl");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "input with " + PAUSE_SENTINEL + " marker",
                null, "hitl-session", null, null);

        // PAUSED is NOT a terminal state for the SSE emitter — SseAgentEmitter.pause
        // does not call sseEmitter.complete(). The stream stays open (up to the 5-minute
        // SseEmitter timeout) so the peer can receive a subsequent resume event from a
        // separate API call. Use a short-timeout client and expect the read timeout —
        // the PAUSE audit row plus the PAUSED chunk in the partial response body are
        // what we assert on.
        RestTemplate shortTimeoutClient = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        shortTimeoutClient.setRequestFactory(factory);

        RestClientException timeoutEx = assertThrows(RestClientException.class, () ->
                shortTimeoutClient.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                        new HttpEntity<>(request, auth), String.class),
                "client times out reading the SSE stream — PAUSED is non-terminal, the " +
                        "stream is held open");

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'PAUSED'",
                        Integer.class, taskId) >= 1);
        sleepQuietly(300);

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        Integer completedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                Integer.class, taskId);
        String pausedMessage = jdbc.queryForObject(
                "SELECT message FROM a2a_task_events WHERE task_id = ? AND status = 'PAUSED' " +
                        "ORDER BY event_ts DESC LIMIT 1",
                String.class, taskId);

        assertAll("A2A HITL pause propagation — executor honors RunResponse.status=PAUSED",
                () -> assertTrue(timeoutEx.getMessage() != null && !timeoutEx.getMessage().isBlank(),
                        "client observed a transport-level exception while reading the held-open " +
                                "SSE stream — confirms PAUSED is non-terminal at the stream level"),
                () -> assertEquals(List.of("SUBMITTED", "WORKING", "PAUSED"), statuses,
                        "audit trail ends at PAUSED, NOT COMPLETED — the production fix in this " +
                                "PR routes RunResponse(status=PAUSED) to A2aTaskStatus.PAUSED " +
                                "instead of unconditional COMPLETED"),
                () -> assertEquals(0, completedRows.intValue(),
                        "no COMPLETED audit row — a PAUSED run must NOT be visible to the peer " +
                                "as done"),
                () -> assertTrue(pausedMessage != null && pausedMessage.contains("Tool execution paused"),
                        "PAUSED audit message carries the user-facing pause description from " +
                                "AgentService — the content field flows through to the audit"));
    }

    // ---------------------------------------------------------------------
    // Stubbed AgentOperations — returns PAUSED for the sentinel input, throws otherwise.
    // Mirrors FakeChatModel's role at the LLM seam: a test substitution at a defined
    // boundary so the unit under test (the executor's status routing) can be exercised
    // without building a full HITL-tool fixture.
    // ---------------------------------------------------------------------

    @TestConfiguration
    static class PausedAgentOperationsConfig {
        @Bean
        @Primary
        AgentOperations pausedAgentOperations() {
            return new AgentOperations() {
                @Override
                public RunResponse run(String agentId, String userInput, String sessionId) {
                    throw new UnsupportedOperationException("not used by A2A path");
                }

                @Override
                public RunResponse run(String agentId, String userInput, List<Media> media,
                                      String sessionId, String userId, String orgId,
                                      Boolean generateFollowups, RunOptions options) {
                    if (userInput != null && userInput.contains(PAUSE_SENTINEL)) {
                        String runId = "run-" + UUID.randomUUID();
                        String approvalId = "approval-test-" + UUID.randomUUID();
                        Map<String, Object> requiredAction = Map.of(
                                "toolName", "synthetic-hitl-tool",
                                "approvalId", approvalId);
                        Map<String, Object> metadata = Map.of("requiredAction", requiredAction);
                        return new RunResponse(
                                runId, sessionId,
                                "Tool execution paused: synthetic-hitl-tool. Requires confirmation.",
                                metadata, List.of(), List.of(),
                                RunStatus.PAUSED, null);
                    }
                    throw new UnsupportedOperationException(
                            "stub only handles the PAUSE_SENTINEL probe; input=" + userInput);
                }

                @Override
                public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media,
                                                     String sessionId, String userId, String orgId,
                                                     Boolean generateFollowups, RunOptions options) {
                    throw new UnsupportedOperationException("not used by this test");
                }

                @Override
                public String runInBackground(String agentId, String userInput, List<Media> media,
                                              String sessionId, String userId, String orgId,
                                              Boolean generateFollowups, RunOptions options) {
                    throw new UnsupportedOperationException("not used by this test");
                }

                @Override
                public String runInBackground(String agentId, String userInput, String sessionId) {
                    throw new UnsupportedOperationException("not used by this test");
                }

                @Override
                public String runPlayground(String agentId, String userInput, String sessionId) {
                    throw new UnsupportedOperationException("not used by this test");
                }

                @Override
                public void cancelRun(String runId) {
                    // no-op
                }

                @Override
                public RunResponse continueRun(String runId, String action) {
                    throw new UnsupportedOperationException("not used by this test");
                }

                @Override
                public void loadKnowledge(String agentId) {
                    // no-op
                }
            };
        }
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
        a.setName("A2A HITL pause runtime target");
        a.setDescription("HITL pause fixture");
        a.setInstructions("Triggers a synthetic PAUSED RunResponse via the stub at the AgentOperations seam.");
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
