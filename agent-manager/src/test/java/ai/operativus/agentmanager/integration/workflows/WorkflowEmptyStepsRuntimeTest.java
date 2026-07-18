package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the zero-step run contract — {@code POST /run} on a workflow with
 *   no steps is rejected with 400 BEFORE any run row or job is created. This is the deliberate
 *   #649 guard: pre-guard, the background job hit a {@code workflow_runs} FK violation and failed
 *   invisibly; the controller now checks {@code countByWorkflowId} and throws
 *   {@code BusinessValidationException} (the same contract the graph editor's Run button relies
 *   on — it disables itself for 0-step workflows because the BE 400s).
 *
 *   <p>Sibling {@link WorkflowsRuntimeTest} covers the non-empty step paths.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, JobQueueTestSupport.class})
public class WorkflowEmptyStepsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;

    @BeforeEach
    void seedModelRow() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void workflowWithZeroSteps_runRejected400_andNoRunOrJobCreated() {
        HttpHeaders auth = newCaller("empty");
        String workflowId = createWorkflow(auth, "Empty Steps Flow");

        long jobsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'WORKFLOW_EXECUTION'", Long.class);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "anything", "sessionId", "sess-empty-" + UUID.randomUUID()), auth),
                JSON_MAP);

        assertAll("zero-step run rejection contract (#649)",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "POST /run on a zero-step workflow must 400 (BusinessValidationException) — "
                                + "pre-guard it minted a job that died on a workflow_runs FK violation"),
                () -> assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                                "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ?",
                                Integer.class, workflowId),
                        "no workflow_runs row may be created for a rejected dispatch"),
                () -> assertEquals(Long.valueOf(jobsBefore), jdbc.queryForObject(
                                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'WORKFLOW_EXECUTION'",
                                Long.class),
                        "no WORKFLOW_EXECUTION job may be enqueued for a rejected dispatch"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "wf-empty-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-wf-empty-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }
}
