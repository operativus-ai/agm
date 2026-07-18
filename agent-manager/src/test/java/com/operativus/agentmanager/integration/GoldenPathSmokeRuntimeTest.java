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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: <strong>Golden-path smoke test for the canonical first-time user
 *   journey.</strong> Composes ten sequential HTTP interactions that a brand-new operator
 *   would perform from sign-up through their first successful agent run, validating that
 *   the product is end-to-end alive.
 *
 *   <p>Every other {@code *RuntimeTest} in this suite is feature-focused — auth here, agents
 *   there, runs elsewhere. They each pass in isolation. This test pins that they all pass
 *   <em>composed</em> in the order a real user would compose them, against the same Spring
 *   context, the same JWT, the same persistence state.
 *
 *   <p>If this test goes red while feature-level tests stay green, an integration boundary
 *   regressed: bodies are accepted but not stored, IDs returned but not queryable, runs
 *   created but not visible to their own creator, etc. Each step's failure message points
 *   at the specific link that broke so triage is line-number-driven.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}
 *   in {@code @AfterEach}; the model row is re-seeded each test in {@code @BeforeEach}.
 *
 * <p>The journey:
 * <ol>
 *   <li>Register a new admin user via {@code POST /api/auth/register}</li>
 *   <li>Login via {@code POST /api/auth/login} → JWT token (covered by
 *       {@link BaseIntegrationTest#authenticateAs})</li>
 *   <li>List agents — empty tenant precondition</li>
 *   <li>Create an agent via {@code POST /api/admin/agents}</li>
 *   <li>List agents — now contains the new agent (read-after-write)</li>
 *   <li>Get agent detail — returns the persisted body</li>
 *   <li>Run the agent synchronously with a scripted FakeChatModel response</li>
 *   <li>Verify the response content matches the scripted reply (advisor chain end-to-end)</li>
 *   <li>List runs filtered by agentId — returns the just-created run</li>
 *   <li>Get run by id — status {@code COMPLETED}, agentId + sessionId echo back</li>
 * </ol>
 */
@Import({
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class
})
public class GoldenPathSmokeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_REF =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAP_REF =
            new ParameterizedTypeReference<>() {};

    private static final String MODEL_ID = "gpt-4o-mini";

    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void truncateAndSeedFreshState() {
        // The base class truncates AFTER each test, not BEFORE. Liquibase seeds a default
        // "assistant" agent into the agents table during migration, which is still present
        // for the FIRST test of any JVM (no prior @AfterEach has fired yet). If this smoke
        // test lands first in the suite order, that seed agent breaks the read-after-write
        // size assertions in steps 3 + 5. Explicitly truncating here makes the test
        // order-independent — the price is one extra TRUNCATE per test run (~1 ms).
        truncateDatabase();
        fakeChatModel.reset();
        // Agent run path requires a ModelEntity row matching the body's "model" field. The
        // FakeChatModelConfig substitutes the real ChatModel beans at runtime; the DB row
        // is the catalog entry the run controller dereferences. Pattern reused from
        // ObservabilityRuntimeTest §20 — kept private here so the smoke test class is
        // self-contained.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, MODEL_ID, MODEL_ID, MODEL_ID);
    }

    @Test
    void firstTimeUserCanRegisterCreateAgentRunAndQueryItsHistory() {
        // ── Step 1+2: Register + login (real /api/auth, real BCrypt, real JWT). ROLE_USER
        // would suffice for runs but the agent-create path requires ROLE_ADMIN per the
        // class-level @PreAuthorize on AgentAdminController. New tenants typically register
        // their first user with admin rights — that is the journey we are smoking.
        HttpHeaders auth = authenticateAs(
                "smoke-golden",
                "smoke-golden@test.local",
                "smoke-pass-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
        assertNotNull(auth.getFirst("Authorization"), "step 2 must produce a Bearer header");

        // ── Step 3: empty-tenant precondition. New tenant must see zero agents before
        // any are created — otherwise a stale-state leak (or cross-tenant exposure) is
        // surfacing rows that should not be visible.
        ResponseEntity<List<Map<String, Object>>> emptyList = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(auth), LIST_OF_MAP_REF);
        assertEquals(HttpStatus.OK, emptyList.getStatusCode(),
                "step 3: list-agents on a fresh tenant must return 200");
        assertTrue(emptyList.getBody() != null && emptyList.getBody().isEmpty(),
                "step 3: empty tenant must see zero agents; got " + emptyList.getBody());

        // ── Step 4: create an agent. Body matches the AgentDefinition contract used by
        // the FE create-agent form. agentId is client-supplied to make the test
        // deterministic against the listing/lookup steps that follow.
        String agentId = "smoke-agent-" + UUID.randomUUID();
        Map<String, Object> createBody = new HashMap<>();
        createBody.put("agentId", agentId);
        createBody.put("name", "Golden Path Smoke Agent");
        createBody.put("description", "Composed end-to-end smoke fixture");
        createBody.put("instructions", "Reply concisely.");
        createBody.put("model", MODEL_ID);
        createBody.put("isReasoningEnabled", false);
        createBody.put("isTeam", false);
        createBody.put("requiresPiiRedaction", false);
        createBody.put("approvedForProduction", false);
        createBody.put("maintenanceMode", false);
        createBody.put("active", true);
        createBody.put("enforceJsonOutput", false);
        createBody.put("memoryEnabled", true);
        createBody.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST,
                new HttpEntity<>(createBody, auth), MAP_REF);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                "step 4: agent create must return 201; got " + created.getStatusCode()
                        + " body=" + created.getBody());
        assertNotNull(created.getBody(), "step 4: create response body must not be null");

        // ── Step 5: read-after-write — the agent the user just created must be visible
        // in the user-side listing endpoint (different controller from the admin POST).
        ResponseEntity<List<Map<String, Object>>> oneAgentList = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(auth), LIST_OF_MAP_REF);
        assertEquals(HttpStatus.OK, oneAgentList.getStatusCode());
        assertEquals(1, oneAgentList.getBody().size(),
                "step 5: tenant list must contain exactly the agent just created");
        assertEquals(agentId, oneAgentList.getBody().get(0).get("agentId"),
                "step 5: list row agentId must match created body");

        // ── Step 6: detail fetch by id — same identity, same body shape.
        ResponseEntity<Map<String, Object>> detail = rest.exchange(
                url("/api/agents/" + agentId), HttpMethod.GET, new HttpEntity<>(auth), MAP_REF);
        assertEquals(HttpStatus.OK, detail.getStatusCode(),
                "step 6: agent detail must return 200 for a just-created agent");
        assertEquals(agentId, detail.getBody().get("agentId"),
                "step 6: detail.agentId must match the created agentId");
        assertEquals("Golden Path Smoke Agent", detail.getBody().get("name"),
                "step 6: detail.name must round-trip from the create body");

        // ── Step 7+8: scripted run. FakeChatModel returns a known reply; the run endpoint
        // must propagate it through the full advisor chain (security, RAG, PII, memory,
        // metrics, logging) and surface it in the RunResponse content field. This is the
        // single most important assertion in the test — a "hello world" run actually works.
        String scriptedReply = "Hello — smoke run reply " + UUID.randomUUID();
        fakeChatModel.respondWith(scriptedReply);

        String sessionId = "smoke-session-" + UUID.randomUUID();
        String userMessage = "ping from the golden path smoke test";
        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", userMessage);
        runBody.put("sessionId", sessionId);

        ResponseEntity<Map<String, Object>> runResp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"), HttpMethod.POST,
                new HttpEntity<>(runBody, auth), MAP_REF);
        assertEquals(HttpStatus.OK, runResp.getStatusCode(),
                "step 7: run must return 200; got " + runResp.getStatusCode()
                        + " body=" + runResp.getBody());
        Map<String, Object> runBodyOut = runResp.getBody();
        assertNotNull(runBodyOut, "step 7: run body must not be null");
        Object content = runBodyOut.get("content");
        assertNotNull(content, "step 8: run response must carry a content field");
        assertEquals(scriptedReply, content.toString(),
                "step 8: run content must echo the scripted FakeChatModel reply exactly. "
                        + "A mismatch means an advisor in the chain is rewriting the response "
                        + "(or the chain is hitting the wrong ChatModel bean).");

        // The fake must have actually received the prompt — otherwise the run returned the
        // scripted text without exercising the advisor chain (false-green).
        assertFalse(fakeChatModel.receivedPrompts().isEmpty(),
                "step 8: FakeChatModel must have recorded the prompt — empty list means the "
                        + "advisor chain bypassed the ChatModel bean entirely");

        String runId = (String) runBodyOut.get("runId");
        assertNotNull(runId, "step 8: run response must carry a runId");

        // ── Step 9: runs listing filtered by agentId. The just-finished run must be
        // queryable in the user-side runs view (powers the Recent Runs UI widget). This
        // composes RunsController + RunRepository.findByAgentIdAndOrgIdOrderByCreatedAtDesc
        // and pins the org-scoped filter wiring.
        ResponseEntity<Map<String, Object>> runsPage = rest.exchange(
                url("/api/v1/runs?agentId=" + agentId), HttpMethod.GET,
                new HttpEntity<>(auth), MAP_REF);
        assertEquals(HttpStatus.OK, runsPage.getStatusCode(),
                "step 9: runs list must return 200");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runRows = (List<Map<String, Object>>) runsPage.getBody().get("content");
        assertNotNull(runRows, "step 9: runs page must carry a content array");
        assertEquals(1, runRows.size(),
                "step 9: filtered run list must contain exactly the one run just created; "
                        + "got " + runRows.size() + " rows");
        // AgentRunResponse uses "id" (not "runId") per the DTO record. The sync-run response
        // shape (POST /api/agents/{id}/runs → RunResponse) uses "runId" — different DTOs
        // for different endpoints. The pin here is that they refer to the same row.
        assertEquals(runId, runRows.get(0).get("id"),
                "step 9: listed run.id must match the runId returned by the run POST");

        // ── Step 10: by-id run detail. Same shape as the list row, but reached through a
        // separate endpoint and tenant-isolated by callerMayReadRun. Status must be
        // COMPLETED — anything else means the synchronous run path returned before the
        // finalizer wrote the terminal status, which would be a release blocker.
        ResponseEntity<Map<String, Object>> runDetail = rest.exchange(
                url("/api/v1/runs/" + runId), HttpMethod.GET,
                new HttpEntity<>(auth), MAP_REF);
        assertEquals(HttpStatus.OK, runDetail.getStatusCode(),
                "step 10: run detail must return 200 for the caller who just created the run");
        assertEquals("COMPLETED", runDetail.getBody().get("status"),
                "step 10: synchronous run must be COMPLETED by the time the POST returns. "
                        + "Any non-terminal status here means the run-status finalizer did "
                        + "not commit before the response landed.");
        assertEquals(agentId, runDetail.getBody().get("agentId"),
                "step 10: run.agentId must echo the agent that owns it");
        assertEquals(sessionId, runDetail.getBody().get("sessionId"),
                "step 10: run.sessionId must echo the sessionId the user supplied");
    }
}
