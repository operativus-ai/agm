package com.operativus.agentmanager.integration;

import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: <strong>Golden-path smoke for the team-orchestration runtime.</strong>
 *   Companion to {@link GoldenPathSmokeRuntimeTest} which exercises the single-agent
 *   journey. This one composes the same shape (register → create → run → query) but
 *   against the multi-agent team path: two child agents wired into a SEQUENTIAL team,
 *   driven via HTTP, member runs visible in the runs list after the team run completes.
 *
 *   <p>The runtime under test is materially different from the single-agent case — it
 *   exercises {@code TeamOrchestrationEngine}, {@code SequentialOrchestrator},
 *   member-dispatch session reuse, team→member orchestration-depth handoff, and the
 *   {@code agent_runs} parent-runId chain. None of that is covered by the single-agent
 *   smoke; a regression in any of those layers passes feature tests but fails this one.
 *
 *   <p>Strategy choice: SEQUENTIAL is the simplest orchestrator with deterministic
 *   member ordering. Router/Planner/Swarm strategies would each warrant their own
 *   smoke once the canonical journey here is stable.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}
 *   in {@code @AfterEach}; an explicit {@code truncateDatabase()} in {@code @BeforeEach}
 *   plus model re-seed makes the test JVM-order-independent (Liquibase seeds a default
 *   "assistant" agent that would otherwise contaminate the size assertion).
 *
 * <p>Journey (all via real HTTP, real JWT, real persistence):
 * <ol>
 *   <li>Register an admin user + log in</li>
 *   <li>Create child agent A ("researcher") via POST /api/admin/agents</li>
 *   <li>Create child agent B ("writer") via POST /api/admin/agents</li>
 *   <li>Create SEQUENTIAL team with members [A, B]</li>
 *   <li>Seed FakeChatModel with two scripted replies (one per member dispatch)</li>
 *   <li>POST /api/agents/{teamId}/runs with a single message</li>
 *   <li>Verify team run returns 200 and final content reflects the orchestrated output</li>
 *   <li>Verify FakeChatModel received exactly two prompts (one per member)</li>
 *   <li>List /api/v1/runs filtered by member agentId — member runs visible to the caller</li>
 * </ol>
 */
@Import({
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class
})
public class TeamOrchestrationGoldenPathSmokeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_REF =
            new ParameterizedTypeReference<>() {};

    private static final String MODEL_ID = "gpt-4o-mini";

    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void truncateAndSeedFreshState() {
        // Same rationale as GoldenPathSmokeRuntimeTest: the base class truncates on
        // @AfterEach only, and Liquibase seeds a default "assistant" agent that
        // contaminates the FIRST test in any JVM. Truncating up-front makes the
        // size + listing assertions order-independent.
        truncateDatabase();
        fakeChatModel.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, MODEL_ID, MODEL_ID, MODEL_ID);
    }

    @Test
    void sequentialTeamRunDispatchesBothMembersAndSurfacesTheirRunsToTheCaller() {
        // ── Step 1: register + login. Admin role required for agent creation.
        HttpHeaders auth = authenticateAs(
                "team-smoke",
                "team-smoke@test.local",
                "team-smoke-pass-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        // ── Step 2+3: create two child agents. The FakeChatModel is shared across them
        // (FIFO queue) — each member dispatch consumes one scripted reply in order.
        String researcherId = createAgent(auth, "team-smoke-researcher",
                "Research the topic and return findings.");
        String writerId = createAgent(auth, "team-smoke-writer",
                "Compose a paragraph from the researcher's findings.");

        // ── Step 4: create the team. teamMode=SEQUENTIAL + members=[A, B] means the
        // orchestrator dispatches A then B with the same session, threading A's
        // output into B's input.
        String teamId = "team-smoke-team-" + UUID.randomUUID();
        Map<String, Object> teamBody = baseAgentBody(teamId, "Team Smoke Orchestrator",
                "Sequential team smoke: researcher → writer.");
        teamBody.put("isTeam", true);
        teamBody.put("teamMode", "SEQUENTIAL");
        teamBody.put("members", List.of(researcherId, writerId));
        ResponseEntity<Map<String, Object>> teamCreate = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST,
                new HttpEntity<>(teamBody, auth), MAP_REF);
        assertEquals(HttpStatus.CREATED, teamCreate.getStatusCode(),
                "step 4: team create must return 201; got " + teamCreate.getStatusCode()
                        + " body=" + teamCreate.getBody());

        // ── Step 5: script FakeChatModel with one reply per expected member dispatch.
        // SEQUENTIAL fires the members in order, FIFO queue dispenses scripts in order.
        String researcherReply = "research finding " + UUID.randomUUID();
        String writerReply = "final composed paragraph " + UUID.randomUUID();
        fakeChatModel.respondWith(researcherReply);
        fakeChatModel.respondWith(writerReply);

        // ── Step 6: run the team via the user-facing run endpoint.
        String sessionId = "team-smoke-session-" + UUID.randomUUID();
        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "Write me a paragraph on virtual threads.");
        runBody.put("sessionId", sessionId);
        ResponseEntity<Map<String, Object>> teamRun = rest.exchange(
                url("/api/agents/" + teamId + "/runs"), HttpMethod.POST,
                new HttpEntity<>(runBody, auth), MAP_REF);

        // ── Step 7: team run completes. The final content is the last member's
        // output (writer) under SEQUENTIAL — that's the orchestrator's documented
        // contract (the tail's reply is what surfaces back to the caller).
        assertEquals(HttpStatus.OK, teamRun.getStatusCode(),
                "step 7: team run must return 200; got " + teamRun.getStatusCode()
                        + " body=" + teamRun.getBody());
        Map<String, Object> teamRunBody = teamRun.getBody();
        assertNotNull(teamRunBody, "step 7: team run body must not be null");
        assertNotNull(teamRunBody.get("runId"), "step 7: team run must return a runId");
        Object content = teamRunBody.get("content");
        assertNotNull(content, "step 7: team run response must carry a content field");
        // The SEQUENTIAL orchestrator returns the last member's reply as the team
        // output. A mismatch here means either (a) the orchestrator returned the
        // wrong member's reply, or (b) the team output is being rewritten by the
        // advisor chain after the last member runs.
        assertEquals(writerReply, content.toString(),
                "step 7: SEQUENTIAL team output must equal the last member's reply. "
                        + "Got: '" + content + "' — if this is the researcher's reply, "
                        + "the orchestrator returned the wrong tail; if it is something "
                        + "else, an advisor in the team chain is rewriting the response.");

        // ── Step 8: FakeChatModel must have received exactly two prompts — one per
        // member dispatch. A different count surfaces a real orchestration bug:
        //   - 0 prompts: orchestrator never dispatched (gate, exception, misconfig)
        //   - 1 prompt:  orchestrator dispatched only one member (member B skipped,
        //                or member A's output not threaded into B)
        //   - 3+ prompts: a retry/loop is firing extra dispatches, OR the @Async
        //                 reflect-on-run path landed despite NoOpReflectionServiceConfig
        assertEquals(2, fakeChatModel.receivedPrompts().size(),
                "step 8: FakeChatModel must have recorded exactly two prompts "
                        + "(one per SEQUENTIAL member dispatch). Got "
                        + fakeChatModel.receivedPrompts().size()
                        + " — see comment above for what each count indicates.");

        // ── Step 9: the member runs must be queryable by their own agentId. This pins
        // that the orchestrator persisted each member dispatch as its own row in
        // agent_runs (with parent_run_id pointing back at the team run). Without that
        // chain the runs page can't render a tree, FinOps can't attribute cost per
        // member, and the audit trail is incomplete.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> researcherRuns = (List<Map<String, Object>>) listRuns(auth, researcherId);
        assertTrue(researcherRuns.size() >= 1,
                "step 9: researcher member run must surface under /api/v1/runs?agentId=" + researcherId
                        + " — got " + researcherRuns.size() + " rows. Empty means the orchestrator "
                        + "ran the member in-memory only without persisting an agent_runs row, "
                        + "which breaks every downstream attribution path.");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writerRuns = (List<Map<String, Object>>) listRuns(auth, writerId);
        assertTrue(writerRuns.size() >= 1,
                "step 9: writer member run must surface under /api/v1/runs?agentId=" + writerId
                        + " — got " + writerRuns.size() + " rows. Same diagnostic as above.");
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String createAgent(HttpHeaders auth, String idPrefix, String instructions) {
        String agentId = idPrefix + "-" + UUID.randomUUID();
        Map<String, Object> body = baseAgentBody(agentId, idPrefix, instructions);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST,
                new HttpEntity<>(body, auth), MAP_REF);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "fixture: child agent '" + idPrefix + "' create must return 201; got "
                        + resp.getStatusCode() + " body=" + resp.getBody());
        return agentId;
    }

    private Map<String, Object> baseAgentBody(String agentId, String name, String instructions) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Team-orchestration smoke fixture");
        body.put("instructions", instructions);
        body.put("model", MODEL_ID);
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", false);
        body.put("addHistoryToMessages", true);
        return body;
    }

    private Object listRuns(HttpHeaders auth, String agentId) {
        ResponseEntity<Map<String, Object>> page = rest.exchange(
                url("/api/v1/runs?agentId=" + agentId), HttpMethod.GET,
                new HttpEntity<>(auth), MAP_REF);
        assertEquals(HttpStatus.OK, page.getStatusCode(),
                "runs list must return 200 for agentId=" + agentId
                        + "; got " + page.getStatusCode());
        return page.getBody().get("content");
    }
}
