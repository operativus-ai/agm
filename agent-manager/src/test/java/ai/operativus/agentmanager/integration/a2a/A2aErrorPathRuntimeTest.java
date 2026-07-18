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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Error-path runtime coverage for the inbound A2A task pipeline.
 *   Three failure modes:
 *   <ol>
 *     <li><b>Validation:</b> {@code @Valid @RequestBody A2aTaskRequest} with blank
 *         {@code input} → Spring rejects pre-controller → 400 → no executor invocation,
 *         no audit rows.</li>
 *     <li><b>Unknown target agent:</b> {@code targetAgentId} that does not resolve in
 *         the registry → {@code AgentOperations.run} throws → executor's catch routes
 *         to the FAILED branch → audit(FAILED) + errorDetail.</li>
 *     <li><b>LLM error mid-flight:</b> {@code FakeChatModel} throws inside the run →
 *         same FAILED branch as above but with a different error message → proves the
 *         catch wires {@code e.getMessage()} into {@code errorDetail}.</li>
 *   </ol>
 *
 *   These pin the executor's failure-routing contract:
 *   <pre>
 *   } catch (Exception e) {
 *       if (interrupted) { ...CANCELLED... }
 *       else { emitter.error(taskId, runId, e.getMessage());
 *              audit(request, runId, FAILED, null, e.getMessage()); }
 *   }
 *   </pre>
 *   {@code A2aTaskCancellationRuntimeTest} pins the CANCELLED branch; this pins FAILED.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aErrorPathRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Bean-validation failure on blank {@code input}. The {@code @NotBlank}
     * constraint on {@link A2aTaskRequest#input()} rejects the request before the
     * controller method body runs. Spring's default handler returns 400; no audit
     * row is written because the executor is never invoked.
     */
    @Test
    void submitTask_blankInput_returns400AndAuditsNothing() {
        String agentId = persistAgent("a2a-target-blank-" + UUID.randomUUID());

        String taskId = "task-blank-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-blank");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "   ",      // blank input — triggers @NotBlank
                null, "blank-session", null, null);

        HttpStatusCode status = postForStatusNoBody("/api/v1/a2a/tasks", request, auth);

        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?",
                Integer.class, taskId);

        assertAll("A2A validation failure on blank input",
                () -> assertEquals(HttpStatus.BAD_REQUEST, status,
                        "controller returns 400 — @NotBlank input rejected by @Valid"),
                () -> assertEquals(0, auditRows,
                        "no audit rows for this taskId — executor was never invoked, " +
                                "validation short-circuited before submitTask ran"));
    }

    /**
     * Case 2 — Unknown target agent. {@code AgentService.run} throws when the agent
     * cannot be resolved. The executor's catch block routes to the FAILED branch
     * because the thread was not interrupted. {@code errorDetail} captures the
     * underlying exception message.
     */
    @Test
    void submitTask_unknownAgent_auditsFailedWithErrorDetail() {
        String missingAgentId = "agent-does-not-exist-" + UUID.randomUUID();

        String taskId = "task-unknown-agent-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-unknown");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, missingAgentId, "input to nowhere",
                null, "unknown-session", null, null);

        HttpStatusCode status = postForStatusNoBody("/api/v1/a2a/tasks", request, auth);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED'",
                        Integer.class, taskId) >= 1);

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        String errorDetail = jdbc.queryForObject(
                "SELECT error_detail FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED' " +
                        "ORDER BY event_ts DESC LIMIT 1",
                String.class, taskId);

        assertAll("A2A unknown target agent — FAILED audit + errorDetail",
                () -> assertEquals(HttpStatus.OK, status,
                        "controller returns 200 — SSE stream opens; failure surfaces as a stream event"),
                () -> assertTrue(statuses.contains("SUBMITTED"),
                        "SUBMITTED audited before the agent-resolve attempt"),
                () -> assertTrue(statuses.contains("FAILED"),
                        "FAILED audited after AgentOperations.run threw"),
                () -> assertEquals("FAILED", statuses.get(statuses.size() - 1),
                        "FAILED is the last audited status — terminal"),
                () -> assertNotNull(errorDetail,
                        "error_detail is non-null — executor wrote e.getMessage() into the audit row"),
                () -> assertTrue(errorDetail.length() > 0,
                        "error_detail carries a non-empty exception message — proves the FAILED " +
                                "branch wired e.getMessage() and not null"));
    }

    /**
     * Case 3 — LLM throws mid-flight. {@code FakeChatModel} responds with a function
     * that throws a {@code RuntimeException}; the exception propagates up through
     * the Spring AI ChatClient and AgentService chain into the executor's catch.
     * Because the thread was not interrupted, the FAILED branch fires.
     */
    @Test
    void submitTask_llmThrows_auditsFailedAndPropagatesMessage() {
        String agentId = persistAgent("a2a-target-llm-fail-" + UUID.randomUUID());

        String boomMessage = "synthetic-llm-failure-" + UUID.randomUUID();
        fakeChatModel.respondWith(p -> {
            throw new RuntimeException(boomMessage);
        });

        String taskId = "task-llm-fail-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-llm-fail");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "input that will fail at the LLM",
                null, "llm-fail-session", null, null);

        HttpStatusCode status = postForStatusNoBody("/api/v1/a2a/tasks", request, auth);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED'",
                        Integer.class, taskId) >= 1);

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        String errorDetail = jdbc.queryForObject(
                "SELECT error_detail FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED' " +
                        "ORDER BY event_ts DESC LIMIT 1",
                String.class, taskId);

        assertAll("A2A LLM throws mid-flight — FAILED audit with the synthetic message",
                () -> assertEquals(HttpStatus.OK, status,
                        "controller returns 200 — SSE stream opens before the LLM call"),
                () -> assertEquals(1, fakeChatModel.receivedPrompts().size(),
                        "FakeChatModel was invoked exactly once — the executor reached the LLM seam"),
                () -> assertTrue(statuses.contains("SUBMITTED"),
                        "SUBMITTED audited from the controller thread"),
                () -> assertTrue(statuses.contains("WORKING"),
                        "WORKING audited before the executor entered the blocking run"),
                () -> assertTrue(statuses.contains("FAILED"),
                        "FAILED audited after the LLM threw"),
                () -> assertEquals("FAILED", statuses.get(statuses.size() - 1),
                        "FAILED is the last audited status — terminal"),
                () -> assertNotNull(errorDetail,
                        "error_detail is non-null"),
                () -> assertTrue(errorDetail.contains(boomMessage),
                        "error_detail surfaces the synthetic LLM exception message — proves " +
                                "the exception travelled cleanly from FakeChatModel through " +
                                "ChatClient + AgentService into the executor's audit write"));
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
        a.setName("A2A error-path runtime target");
        a.setDescription("A2A error-path runtime fixture");
        a.setInstructions("Target for FAILED-branch coverage.");
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
