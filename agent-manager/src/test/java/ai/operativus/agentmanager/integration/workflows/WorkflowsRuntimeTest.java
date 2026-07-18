package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.core.model.WorkflowDTO;
import ai.operativus.agentmanager.core.model.WorkflowStepDTO;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Workflows surface —
 *   {@link ai.operativus.agentmanager.control.controller.WorkflowsController} plus the
 *   {@link ai.operativus.agentmanager.control.service.WorkflowService} execution engine.
 *   Exercises template CRUD, clone, async execution via the
 *   {@link ai.operativus.agentmanager.control.service.queue.WorkflowExecutionJobHandler},
 *   paused-run resume via the
 *   {@link ai.operativus.agentmanager.control.service.queue.WorkflowResumeJobHandler},
 *   the CONDITION step short-circuit, the HITL PAUSED_SENTINEL handshake, failure
 *   propagation to {@code workflow_runs.status=FAILED}, the
 *   {@link WorkflowStepExecutorExtension} SPI dispatch path, and concurrent run
 *   isolation.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T035.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link ai.operativus.agentmanager.control.service.WorkflowService#executeWorkflowAsync}
 *     saves the initial {@code WorkflowRun} synchronously THEN fires the per-run
 *     executor on a virtual thread via {@code Executors.newVirtualThreadPerTaskExecutor()}.
 *     The job handler returns as soon as that virtual thread is dispatched — NOT when the
 *     workflow finishes. Consequence: awaiting job COMPLETED is insufficient. Tests must
 *     poll {@code workflow_runs.status} directly via {@link Awaitility} to see the final
 *     state.
 *   - The {@code /run} and {@code /resume} endpoints are not RBAC-gated at method level —
 *     any authenticated principal can hit them. Seeding auth via
 *     {@link BaseIntegrationTest#authenticateAs} is required only to pass the global
 *     security filter chain.
 *   - The real {@link AgentOperations} impl ({@code AgentService}) is replaced here by a
 *     recording stub via {@code @Primary}. This bypasses the ChatClient advisor chain
 *     entirely — these tests do NOT cover PII anonymization, RAG, tool-call loops, or
 *     agent memory. Those paths are covered by the dedicated per-feature suites.
 *     The override is possible because {@code application-test.properties} sets
 *     {@code spring.main.allow-bean-definition-overriding=true}.
 *   - The CONDITION step type uses {@code step.getAgentId()} as the condition
 *     expression (e.g., {@code "contains:stop"}). Semantics: when the condition
 *     evaluates FALSE the orchestrator SKIPS the next step; when TRUE it falls through.
 *     Pinned in case 7 so a future inversion of the skip/keep contract flips a clear
 *     assertion.
 *   - The SPI extension lookup wins over the standard AGENT path: if any
 *     {@link WorkflowStepExecutorExtension} supports the step's {@code agentId} string,
 *     the agent operations stub is NOT invoked and the extension's return value
 *     becomes the step output. Case 9 pins this precedence.
 *   - {@code workflow_runs.current_payload} is TEXT (not JSONB) — step output is stored
 *     as a raw string with no JSON cast required.
 */
@Import({
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class,
        WorkflowsRuntimeTest.TestStubsConfig.class,
        JobQueueTestSupport.class})
public class WorkflowsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;
    @Autowired private RecordingAgentOperations runner;
    @Autowired private TestSpiExecutor spiExecutor;

    @BeforeEach
    void resetStubsAndSeedModel() {
        runner.reset();
        spiExecutor.reset();
        // agents.model_id FK → models.id — seed before any agent insert.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // ─── Case 1: Workflow CRUD + paginated list ──────────────────────────────

    @Test
    void workflowCrud_createGetListPatchDelete_persistsAndRoundTripsThroughController() {
        HttpHeaders auth = auth("wf-crud");

        WorkflowDTO created = assertPostOk(
                "/api/v1/workflows",
                new WorkflowDTO(null, "Research Flow", "A research pipeline", 0, null, null),
                auth, WorkflowDTO.class, HttpStatus.CREATED).getBody();

        assertNotNull(created.id(), "POST must assign an id when the payload omits one — null here means the UUID fallback in WorkflowService.createWorkflow:100 regressed");
        assertEquals("Research Flow", created.name());

        ResponseEntity<WorkflowDTO> get = authorizedGet("/api/v1/workflows/" + created.id(), auth, WorkflowDTO.class);
        assertEquals(HttpStatus.OK, get.getStatusCode());
        assertEquals(created.id(), get.getBody().id());
        assertEquals("A research pipeline", get.getBody().description());

        // PATCH name → roundtrip
        ResponseEntity<WorkflowDTO> patched = rest.exchange(
                url("/api/v1/workflows/" + created.id()),
                HttpMethod.PATCH,
                new HttpEntity<>(new WorkflowDTO(null, "Research Flow v2", null, 0, null, null), auth),
                WorkflowDTO.class);
        assertEquals(HttpStatus.OK, patched.getStatusCode());
        assertEquals("Research Flow v2", patched.getBody().name(),
                "PATCH with only `name` must update name only — description unchanged means the WorkflowService null-check on PATCH is working");
        assertEquals("A research pipeline", patched.getBody().description(),
                "PATCH with null description must NOT clobber the persisted value — regression here means the partial-update null-coalesce was removed");

        // Create a second so list has >1 and paging is exercised
        authorizedPost("/api/v1/workflows",
                new WorkflowDTO(null, "Second Flow", "desc", 0, null, null),
                auth, WorkflowDTO.class);

        ResponseEntity<Map<String, Object>> list = authorizedGet("/api/v1/workflows?page=0&size=10", auth, (Class<Map<String, Object>>)(Class<?>)Map.class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) list.getBody().get("content");
        assertEquals(2, content.size(),
                "paginated list must return both created workflows — a 0/1 size here means Page.map in WorkflowService.getAllWorkflows(Pageable) lost content");

        // DELETE + 404 on subsequent GET
        ResponseEntity<Void> del = rest.exchange(
                url("/api/v1/workflows/" + created.id()), HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());

        ResponseEntity<WorkflowDTO> after = authorizedGet("/api/v1/workflows/" + created.id(), auth, WorkflowDTO.class);
        assertEquals(HttpStatus.NOT_FOUND, after.getStatusCode(),
                "GET after DELETE must return 404 — 200 means the delete did not commit, or the getWorkflowById Optional is returning a stale cache");
    }

    // ─── Case 2: Step CRUD — order-by-stepOrder + cascade on workflow delete ─

    @Test
    void workflowSteps_addOutOfOrder_listReturnsOrderedByStepOrder_deleteCascadesOnParentDelete() {
        HttpHeaders auth = auth("wf-steps");
        String workflowId = createWorkflow(auth, "Steps Workflow", "").id();
        String agentA = seedAgent("wf-steps-a");
        String agentB = seedAgent("wf-steps-b");
        String agentC = seedAgent("wf-steps-c");

        // Add steps OUT of step_order to prove the list endpoint sorts, not the insertion order.
        authorizedPost("/api/v1/workflows/" + workflowId + "/steps",
                new WorkflowStepDTO(null, workflowId, 3, agentC, "AGENT", null, null, null, null, null, null, null), auth, WorkflowStepDTO.class);
        authorizedPost("/api/v1/workflows/" + workflowId + "/steps",
                new WorkflowStepDTO(null, workflowId, 1, agentA, "AGENT", null, null, null, null, null, null, null), auth, WorkflowStepDTO.class);
        WorkflowStepDTO step2 = authorizedPost("/api/v1/workflows/" + workflowId + "/steps",
                new WorkflowStepDTO(null, workflowId, 2, agentB, "AGENT", null, null, null, null, null, null, null), auth, WorkflowStepDTO.class).getBody();

        ResponseEntity<List<WorkflowStepDTO>> listResp = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/steps"), HttpMethod.GET,
                new HttpEntity<>(auth), new ParameterizedTypeReference<List<WorkflowStepDTO>>() {});
        assertEquals(HttpStatus.OK, listResp.getStatusCode());
        List<WorkflowStepDTO> ordered = listResp.getBody();
        assertAll("GET /steps returns in stepOrder ASC regardless of insertion order",
                () -> assertEquals(3, ordered.size()),
                () -> assertEquals(agentA, ordered.get(0).agentId(),
                        "first step by stepOrder=1 — a different agent here means findByWorkflowIdOrderByStepOrderAsc lost its ORDER BY"),
                () -> assertEquals(agentB, ordered.get(1).agentId()),
                () -> assertEquals(agentC, ordered.get(2).agentId()));

        // The workflows LIST endpoint must report stepCount=3 for this workflow — the list DTO
        // carries a batch-counted stepCount (the FE "Steps" column reads it). A 0 here means the
        // count never made it into WorkflowDTO / withStepCounts regressed back to the old shape.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listContent = (List<Map<String, Object>>) authorizedGet(
                "/api/v1/workflows?page=0&size=100", auth, (Class<Map<String, Object>>)(Class<?>)Map.class)
                .getBody().get("content");
        Map<String, Object> thisWf = listContent.stream()
                .filter(w -> workflowId.equals(w.get("id"))).findFirst().orElseThrow();
        assertEquals(3, ((Number) thisWf.get("stepCount")).intValue(),
                "workflows list must surface the real step count (3) for the FE Steps column");

        // Delete ONE step → list shrinks
        ResponseEntity<Void> del = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/steps/" + step2.id()),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE workflow_id = ?", Integer.class, workflowId);
        assertEquals(2, remaining);

        // DELETE parent workflow → FK ON DELETE CASCADE nukes remaining steps
        rest.exchange(url("/api/v1/workflows/" + workflowId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        Integer afterParentDelete = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE workflow_id = ?", Integer.class, workflowId);
        assertEquals(0, afterParentDelete,
                "parent DELETE must cascade to workflow_steps — a non-zero count means 001-schema.sql lost the ON DELETE CASCADE on workflow_steps.workflow_id");
    }

    // ─── Case 3: Clone workflow + steps ──────────────────────────────────────

    @Test
    void cloneWorkflow_deepClonesNameAndSteps_andReturns404ForUnknownId() {
        HttpHeaders auth = auth("wf-clone");
        String sourceId = createWorkflow(auth, "Source Pipeline", "desc").id();
        String agentA = seedAgent("wf-clone-a");
        String agentB = seedAgent("wf-clone-b");
        authorizedPost("/api/v1/workflows/" + sourceId + "/steps",
                new WorkflowStepDTO(null, sourceId, 1, agentA, "AGENT", null, null, null, null, null, null, null), auth, WorkflowStepDTO.class);
        authorizedPost("/api/v1/workflows/" + sourceId + "/steps",
                new WorkflowStepDTO(null, sourceId, 2, agentB, "AGENT", null, null, null, null, null, null, null), auth, WorkflowStepDTO.class);

        ResponseEntity<WorkflowDTO> cloneResp = authorizedPost(
                "/api/v1/workflows/" + sourceId + "/clone", null, auth, WorkflowDTO.class);
        assertEquals(HttpStatus.CREATED, cloneResp.getStatusCode());
        WorkflowDTO cloned = cloneResp.getBody();

        assertAll("clone carries suffixed name + independent id + all steps",
                () -> assertNotEquals(sourceId, cloned.id(),
                        "cloned workflow must have a fresh UUID — same id means cloneWorkflow is overwriting the source row in-place"),
                () -> assertEquals("Source Pipeline (Copy)", cloned.name(),
                        "clone appends ' (Copy)' — a name mismatch means the suffix literal drifted"));

        Integer clonedStepCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE workflow_id = ?",
                Integer.class, cloned.id());
        assertEquals(2, clonedStepCount,
                "both source steps must be copied to the clone — a mismatch here means cloneWorkflow's for-loop regressed");

        // 404 on unknown source
        ResponseEntity<WorkflowDTO> notFound = authorizedPost(
                "/api/v1/workflows/does-not-exist/clone", null, auth, WorkflowDTO.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }

    // ─── Case 4: Async execution end-to-end ──────────────────────────────────

    @Test
    void runEndpoint_enqueuesWorkflowExecutionJob_andWorkflowRunReachesCompletedAfterVirtualThreadFinishes() {
        HttpHeaders auth = auth("wf-run");
        String workflowId = createWorkflow(auth, "Runnable", "").id();
        String a1 = seedAgent("wf-run-a1");
        String a2 = seedAgent("wf-run-a2");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a1);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 2, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a2);

        seedSession("sess-run-1");
        runner.scriptResponse("step-1-out");
        runner.scriptResponse("step-2-final");

        ResponseEntity<Map<String, String>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "go", "sessionId", "sess-run-1"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode(),
                "POST /run must return 202 — 200 means the controller accidentally started gating on sync completion");
        String jobId = accepted.getBody().get("jobId");
        assertNotNull(jobId);
        assertEquals(workflowId, accepted.getBody().get("workflowId"));
        assertEquals("sess-run-1", accepted.getBody().get("sessionId"));

        // Drain the queue — production @Scheduled interval pushed to 24h in test props.
        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));

        // Await WorkflowRun.status — virtual thread finishes AFTER job COMPLETES.
        awaitWorkflowRunStatus(workflowId, RunStatus.COMPLETED, Duration.ofSeconds(10));

        Map<String, Object> runRow = jdbc.queryForMap(
                "SELECT status, current_step_order, current_payload FROM workflow_runs WHERE workflow_id = ?", workflowId);
        assertAll("workflow_runs row reflects terminal state",
                () -> assertEquals("COMPLETED", runRow.get("status")),
                () -> assertEquals(2, runRow.get("current_step_order"),
                        "current_step_order advances with each step — stuck at 1 means the loop only ran the first step"),
                () -> assertEquals("step-2-final", runRow.get("current_payload"),
                        "final payload must be the last step's output — a mismatch means the chain failed to thread outputs forward"));

        assertAll("runner saw both step invocations in order",
                () -> assertEquals(2, runner.calls.size()),
                () -> assertEquals(a1, runner.calls.get(0).agentId()),
                () -> assertEquals("go", runner.calls.get(0).input(),
                        "step 1 receives the POST body input verbatim"),
                () -> assertEquals(a2, runner.calls.get(1).agentId()),
                () -> assertEquals("step-1-out", runner.calls.get(1).input(),
                        "step 2's input MUST equal step 1's output — the chain contract"));
    }

    // ─── Case 5: Resume PAUSED workflow run ──────────────────────────────────

    @Test
    void resumePausedRun_picksUpFromNextStepAndReachesCompleted() {
        HttpHeaders auth = auth("wf-resume");
        String workflowId = createWorkflow(auth, "Resume Flow", "").id();
        String a1 = seedAgent("wf-resume-a1");
        String a2 = seedAgent("wf-resume-a2");
        String a3 = seedAgent("wf-resume-a3");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a1);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 2, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a2);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 3, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a3);

        seedSession("sess-resume");
        // Seed a PAUSED WorkflowRun at currentStepOrder=1 — resume should execute steps 2 and 3.
        // workflow_runs.org_id is NOT NULL and the resume controller gates on it matching
        // the caller's org; reuse the workflow's own org_id so the seed is consistent.
        String wfOrgIdResume = jdbc.queryForObject(
                "SELECT org_id FROM workflows WHERE id = ?", String.class, workflowId);
        String runId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status, current_step_order, current_payload, org_id, created_at, updated_at)
                VALUES (?, ?, 'sess-resume', 'PAUSED', 1, 'step-1-output-before-pause', ?, now(), now())
                """, runId, workflowId, wfOrgIdResume);

        runner.scriptResponse("step-2-resumed");
        runner.scriptResponse("step-3-final");

        ResponseEntity<Map<String, String>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/resume"), HttpMethod.POST,
                new HttpEntity<>(Map.of("output", "human-approved-payload"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        String jobId = resp.getBody().get("jobId");

        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
        awaitWorkflowRunStatus(workflowId, RunStatus.COMPLETED, Duration.ofSeconds(10));

        Map<String, Object> runRow = jdbc.queryForMap(
                "SELECT status, current_step_order, current_payload FROM workflow_runs WHERE id = ?", runId);
        assertEquals("COMPLETED", runRow.get("status"));
        assertEquals(3, runRow.get("current_step_order"),
                "resumed run must advance to the final step — stuck on 2 means the remaining-steps filter boundary is wrong");
        assertEquals("step-3-final", runRow.get("current_payload"));

        assertAll("runner was called ONLY for the remaining steps (2 and 3), not step 1",
                () -> assertEquals(2, runner.calls.size(),
                        "3 calls here means resume re-ran step 1 — violates the filter stepOrder > currentStepOrder"),
                () -> assertEquals(a2, runner.calls.get(0).agentId()),
                () -> assertEquals("human-approved-payload", runner.calls.get(0).input(),
                        "first post-resume step input MUST be the preCalculatedOutput — the whole HITL handshake"),
                () -> assertEquals(a3, runner.calls.get(1).agentId()),
                () -> assertEquals("step-2-resumed", runner.calls.get(1).input()));
    }

    // ─── Case 6: Resume on non-PAUSED run is a no-op ────────────────────────

    @Test
    void resumeRun_whenNotPaused_logsWarningAndLeavesRowUnchanged() {
        HttpHeaders auth = auth("wf-resume-guard");
        String workflowId = createWorkflow(auth, "No-op Resume", "").id();
        String a1 = seedAgent("wf-guard-a1");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a1);

        seedSession("sess-guard");
        // Match the workflow's own org_id so the resume tenant guard doesn't 404.
        String wfOrgIdGuard = jdbc.queryForObject(
                "SELECT org_id FROM workflows WHERE id = ?", String.class, workflowId);
        String runId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status, current_step_order, current_payload, org_id, created_at, updated_at)
                VALUES (?, ?, 'sess-guard', 'RUNNING', 1, 'mid-flight', ?, now(), now())
                """, runId, workflowId, wfOrgIdGuard);

        ResponseEntity<Map<String, String>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/resume"), HttpMethod.POST,
                new HttpEntity<>(Map.of("output", "ignored"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "controller does not gate status — it returns 202 even for non-PAUSED runs; the service layer is the guard");
        String jobId = resp.getBody().get("jobId");

        jobs.processNow();
        // Handler itself completes — guard is inside WorkflowService.resumeWorkflowRun.
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));

        // Allow a brief moment for any (incorrectly-fired) virtual thread to mutate state.
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, current_step_order, current_payload FROM workflow_runs WHERE id = ?", runId);
        assertAll("row is untouched — guard in WorkflowService.resumeWorkflowRun:403 fired",
                () -> assertEquals("RUNNING", row.get("status"),
                        "status must stay RUNNING — PAUSED→RUNNING flip here would mean the guard was removed"),
                () -> assertEquals(1, row.get("current_step_order")),
                () -> assertEquals("mid-flight", row.get("current_payload")),
                () -> assertEquals(0, runner.calls.size(),
                        "no agent calls — runner invocation would mean the virtual-thread loop ran after the guard"));
    }

    // ─── Case 7: CONDITION step skips next when evaluation returns false ─────

    @Test
    void conditionStep_whenConditionReturnsFalse_skipsNextStep_andProceeds() {
        HttpHeaders auth = auth("wf-cond");
        String workflowId = createWorkflow(auth, "Conditional", "").id();
        String skippedAgent = seedAgent("wf-cond-skipped");
        String finalAgent = seedAgent("wf-cond-final");

        // CONDITION step whose agentId field stores the condition expression. "contains:go"
        // evaluates TRUE when the input contains "go" — so with input "halt" it returns
        // FALSE and the NEXT step (skippedAgent) is skipped.
        // Note: workflow_steps.agent_id has an FK to agents.id — seed a placeholder
        // agents row named with the condition expression. WorkflowService never invokes
        // AgentOperations on CONDITION steps; the agent row exists solely to satisfy FK.
        seedAgentWithId("contains:go", "cond-placeholder");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'CONDITION')",
                UUID.randomUUID().toString(), workflowId, "contains:go");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 2, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, skippedAgent);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 3, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, finalAgent);

        seedSession("sess-cond");
        // Only the final (step 3) should run — script a single response.
        runner.scriptResponse("final-output");

        ResponseEntity<Map<String, String>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "halt", "sessionId", "sess-cond"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(accepted.getBody().get("jobId"), Duration.ofSeconds(10));
        awaitWorkflowRunStatus(workflowId, RunStatus.COMPLETED, Duration.ofSeconds(10));

        assertAll("condition false → step 2 skipped → only step 3 runs",
                () -> assertEquals(1, runner.calls.size(),
                        "runner called 2× means the skip branch in WorkflowService.executeWorkflowAsync regressed — the 'i++' in the CONDITION branch is load-bearing"),
                () -> assertEquals(finalAgent, runner.calls.get(0).agentId(),
                        "the surviving call must be the FINAL agent, not the skipped one — wrong agent means the skip chose the wrong neighbor"));
    }

    // ─── Case 8: Step failure marks WorkflowRun FAILED ───────────────────────

    @Test
    void agentStepFailure_causesWorkflowRunToTerminateInFailedStatus() {
        HttpHeaders auth = auth("wf-fail");
        String workflowId = createWorkflow(auth, "Failing Flow", "").id();
        String a1 = seedAgent("wf-fail-a1");
        String a2 = seedAgent("wf-fail-a2");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a1);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 2, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, a2);

        seedSession("sess-fail");
        runner.scriptResponse("first-ok");
        runner.scriptThrow(new RuntimeException("boom — simulated agent failure"));

        ResponseEntity<Map<String, String>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "go", "sessionId", "sess-fail"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        jobs.processNow();
        // A FAILED run is a NON-retryable terminal outcome: the handler throws
        // NonRetryableJobException, so the job goes straight to the DLQ — it is NOT
        // retried (retrying would re-run the workflow and mint a second run row).
        jobs.awaitJobFailure(accepted.getBody().get("jobId"), Duration.ofSeconds(10));

        // The job is terminal ⇒ the run already reached FAILED. Assert EXACTLY ONE
        // workflow_runs row: before the no-retry fix, the queue re-ran the workflow up to
        // maxAttempts times, each call minting a fresh row (Bug: duplicate runs on failure).
        Integer runCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ?", Integer.class, workflowId);
        assertEquals(1, runCount,
                "a single /run that fails must produce exactly one workflow_runs row — >1 means the "
                + "job was retried and executeWorkflowAsync re-ran the workflow from scratch");

        String status = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE workflow_id = ?", String.class, workflowId);
        assertEquals("FAILED", status,
                "uncaught step exception must flip status to FAILED — COMPLETED here means the catch-block in executeWorkflowAsync swallowed the error without updating state");
    }

    // ─── Case 9: SPI extension handles a matching step agentId ───────────────

    @Test
    void spiExecutorExtension_whenSupportsReturnsTrue_runsExtensionInPlaceOfAgentAndRecordsOutput() {
        HttpHeaders auth = auth("wf-spi");
        String workflowId = createWorkflow(auth, "SPI Workflow", "").id();
        String realAgent = seedAgent("wf-spi-agent");

        // Step 1 routes through the SPI extension (supports("spi::transform")).
        // Step 2 uses a real agent id — falls through to the AgentOperations stub.
        // FK on workflow_steps.agent_id → agents.id forces us to seed a placeholder
        // agents row named with the executor id. The SPI's supports() returns true
        // before WorkflowService falls back to AgentOperations, so the placeholder
        // is never invoked at runtime.
        seedAgentWithId("spi::transform", "spi-placeholder");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, "spi::transform");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 2, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, realAgent);

        seedSession("sess-spi");
        spiExecutor.configure("spi::transform", in -> "SPI:" + in);
        runner.scriptResponse("agent-after-spi");

        ResponseEntity<Map<String, String>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "hello", "sessionId", "sess-spi"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(accepted.getBody().get("jobId"), Duration.ofSeconds(10));
        awaitWorkflowRunStatus(workflowId, RunStatus.COMPLETED, Duration.ofSeconds(10));

        assertAll("SPI precedence: extension runs for step 1, agent stub runs only for step 2",
                () -> assertEquals(1, spiExecutor.calls.size(),
                        "SPI was NOT called — either supports() is miswired or WorkflowService stopped consulting extensionList"),
                () -> assertEquals("spi::transform", spiExecutor.calls.get(0).executorId),
                () -> assertEquals("hello", spiExecutor.calls.get(0).input),
                () -> assertEquals(1, runner.calls.size(),
                        "runner invocation count ≠ 1 means the SPI either didn't pre-empt step 1 or swallowed step 2's dispatch"),
                () -> assertEquals(realAgent, runner.calls.get(0).agentId()),
                () -> assertEquals("SPI:hello", runner.calls.get(0).input(),
                        "step 2 input MUST be the SPI extension's output — a mismatch means the chain dropped the extension result"));

        String finalPayload = jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE workflow_id = ?", String.class, workflowId);
        assertEquals("agent-after-spi", finalPayload);
    }

    // ─── Case 10: Two concurrent workflow runs complete independently ────────

    @Test
    void concurrentWorkflows_runIndependentlyWithDistinctRunIdsAndSessions() {
        HttpHeaders auth = auth("wf-concurrent");
        String wfA = createWorkflow(auth, "Workflow A", "").id();
        String wfB = createWorkflow(auth, "Workflow B", "").id();
        String agentA = seedAgent("wf-conc-a");
        String agentB = seedAgent("wf-conc-b");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'AGENT')",
                UUID.randomUUID().toString(), wfA, agentA);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, 1, ?, 'AGENT')",
                UUID.randomUUID().toString(), wfB, agentB);

        seedSession("sess-A");
        seedSession("sess-B");
        // Script two distinct responses — stub returns in FIFO order across concurrent callers.
        // Order per agent doesn't matter because we assert on run row state keyed by workflow_id.
        runner.scriptResponse("A-result");
        runner.scriptResponse("B-result");

        ResponseEntity<Map<String, String>> acceptedA = rest.exchange(
                url("/api/v1/workflows/" + wfA + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "a-input", "sessionId", "sess-A"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        ResponseEntity<Map<String, String>> acceptedB = rest.exchange(
                url("/api/v1/workflows/" + wfB + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "b-input", "sessionId", "sess-B"), auth),
                new ParameterizedTypeReference<Map<String, String>>() {});
        assertEquals(HttpStatus.ACCEPTED, acceptedA.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, acceptedB.getStatusCode());

        jobs.processNow();
        jobs.awaitJobSuccess(acceptedA.getBody().get("jobId"), Duration.ofSeconds(10));
        jobs.awaitJobSuccess(acceptedB.getBody().get("jobId"), Duration.ofSeconds(10));

        awaitWorkflowRunStatus(wfA, RunStatus.COMPLETED, Duration.ofSeconds(10));
        awaitWorkflowRunStatus(wfB, RunStatus.COMPLETED, Duration.ofSeconds(10));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT workflow_id, session_id, status, current_payload FROM workflow_runs ORDER BY workflow_id");
        assertEquals(2, rows.size(),
                "two rows expected — a single row would mean one run clobbered the other, likely a shared WorkflowRun id");

        Map<String, Map<String, Object>> byWf = new HashMap<>();
        for (Map<String, Object> r : rows) byWf.put((String) r.get("workflow_id"), r);
        assertAll("each workflow has a distinct terminal row",
                () -> assertEquals("sess-A", byWf.get(wfA).get("session_id")),
                () -> assertEquals("sess-B", byWf.get(wfB).get("session_id"),
                        "session ids bled across runs — virtual-thread isolation regression"),
                () -> assertEquals("COMPLETED", byWf.get(wfA).get("status")),
                () -> assertEquals("COMPLETED", byWf.get(wfB).get("status")),
                // payload assignment is order-dependent across the FIFO script — but either permutation
                // is a valid COMPLETED shape. Assert that both are present and distinct.
                () -> {
                    String pA = (String) byWf.get(wfA).get("current_payload");
                    String pB = (String) byWf.get(wfB).get("current_payload");
                    assertNotNull(pA);
                    assertNotNull(pB);
                    assertNotEquals(pA, pB, "payloads matched across runs — scripted responses leaked between runs");
                });
    }

    // ─── R5 RBAC: definition mutations require admin ─────────────────────────

    /**
     * R5 production fix. {@link ai.operativus.agentmanager.control.controller.WorkflowsController}
     *   now carries {@code @PreAuthorize("hasRole('ADMIN')")} on the six definition-mutating
     *   endpoints (POST /, PATCH /{id}, DELETE /{id}, POST /{id}/steps,
     *   DELETE /{id}/steps/{stepId}, POST /{id}/clone). The "run" endpoints
     *   (POST /{id}/run, POST /runs/{runId}/resume, DELETE /runs/{runId}) intentionally
     *   stay at class-level {@code @PreAuthorize("isAuthenticated()")} — admin manages
     *   the DEFINITION, any authenticated user runs the EXECUTION.
     *
     * <p>This test covers POST (create) as the canonical regression-lock. PATCH / DELETE /
     * step mutations / clone share the same method-security gate; one positive case is
     * sufficient since the @PreAuthorize wiring is uniform.</p>
     */
    @Test
    void workflowDefinitionMutationsRequireAdmin_403ForRoleUser_R5ProductionFix() {
        HttpHeaders userOnly = authRoleUserOnly("wf-rbac-user");

        ResponseEntity<Map<String, Object>> create = rest.exchange(
                url("/api/v1/workflows"), HttpMethod.POST,
                new HttpEntity<>(new WorkflowDTO(null, "R5 guard probe", "should not land", 0, null, null), userOnly),
                JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, create.getStatusCode(),
                "ROLE_USER caller must be rejected by WorkflowsController.createWorkflow @PreAuthorize");

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflows WHERE name = ?", Integer.class, "R5 guard probe");
        assertEquals(0, rowCount,
                "rejected POST must NOT persist a row — rejection must happen before WorkflowService.createWorkflow runs");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders auth(String label) {
        // Admin role required by WorkflowsController @PreAuthorize gates on the definition
        // mutating endpoints (POST, PATCH /{id}, DELETE /{id}, POST /{id}/steps,
        // DELETE /{id}/steps/{stepId}, POST /{id}/clone). Tests in this class exercise
        // those endpoints to set up fixtures, so the default helper grants admin.
        // Use {@link #authRoleUserOnly(String)} for negative RBAC assertions.
        return authenticateAs(label + "-" + UUID.randomUUID(),
                label + "@t.local", "pwd-wf-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders authRoleUserOnly(String label) {
        return authenticateAs(label + "-" + UUID.randomUUID(),
                label + "@t.local", "pwd-wf-1234", List.of("ROLE_USER"));
    }

    private WorkflowDTO createWorkflow(HttpHeaders auth, String name, String desc) {
        return assertPostOk("/api/v1/workflows",
                new WorkflowDTO(null, name, desc, 0, null, null),
                auth, WorkflowDTO.class, HttpStatus.CREATED).getBody();
    }

    private <T> ResponseEntity<T> assertPostOk(String path, Object body, HttpHeaders auth,
                                               Class<T> type, HttpStatus expected) {
        ResponseEntity<T> resp = authorizedPost(path, body, auth, type);
        assertEquals(expected, resp.getStatusCode(), "POST " + path + " returned " + resp.getStatusCode());
        return resp;
    }

    private String seedAgent(String label) {
        String id = "agent-" + label + "-" + UUID.randomUUID();
        seedAgentWithId(id, "Workflow Test Agent " + label);
        return id;
    }

    // For CONDITION / SPI step types, WorkflowService reads step.getAgentId() as the
    // condition expression or extension executorId — but the DB still enforces the FK
    // (workflow_steps.agent_id → agents.id). Seed a placeholder agents row with the
    // exact string so the INSERT passes FK validation. The agent is never actually
    // "run" for these step types; the string is just a payload for the orchestrator.
    private void seedAgentWithId(String id, String name) {
        // org_id MUST be the caller's org (DEFAULT_SYSTEM_ORG) — addWorkflowStep validates
        // AGENT-action steps via agentRepository.existsByIdAndOrgId(agentId, callerOrgId()).
        // A null org_id here makes that check fail, the step POST 404s, and nothing persists.
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', 'DEFAULT_SYSTEM_ORG', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, name);
    }

    // workflow_runs.session_id → agent_sessions(session_id) (016 fk) — must pre-seed or
    // the handler's INSERT rolls back with an FK violation and the job DLQs.
    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'wf-user', 'wf-org', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private void awaitWorkflowRunStatus(String workflowId, RunStatus target, Duration timeout) {
        Awaitility.await("workflow_runs.status=" + target + " for workflow " + workflowId)
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = ?",
                            Integer.class, workflowId, target.name());
                    return n != null && n >= 1;
                });
    }

    // ─── Test config: SPI extension stub (RecordingAgentOperations comes from ──
    // ─── support/RecordingAgentOperationsConfig imported above) ──────────────

    @TestConfiguration
    public static class TestStubsConfig {
        @Bean
        public TestSpiExecutor testSpiExecutor() {
            return new TestSpiExecutor();
        }
    }

    /**
     * Registrable {@link WorkflowStepExecutorExtension}. A test calls {@link #configure}
     * before running the workflow to declare which executorId it answers for and how the
     * input transforms. Without configure, supports() returns false.
     */
    public static class TestSpiExecutor implements WorkflowStepExecutorExtension {
        private final Map<String, java.util.function.Function<String, String>> supported = new ConcurrentHashMap<>();
        final List<SpiCall> calls = new CopyOnWriteArrayList<>();

        public void configure(String executorId, java.util.function.Function<String, String> transform) {
            supported.put(executorId, transform);
        }

        public void reset() { supported.clear(); calls.clear(); }

        @Override
        public boolean supports(String executorId) {
            return executorId != null && supported.containsKey(executorId);
        }

        @Override
        public String executeStep(String executorId, String workflowId, String runId, String inputPayload, Map<String, Object> context) {
            calls.add(new SpiCall(executorId, workflowId, runId, inputPayload));
            java.util.function.Function<String, String> fn = supported.get(executorId);
            return fn != null ? fn.apply(inputPayload) : inputPayload;
        }
    }

    public record SpiCall(String executorId, String workflowId, String runId, String input) {}
}
