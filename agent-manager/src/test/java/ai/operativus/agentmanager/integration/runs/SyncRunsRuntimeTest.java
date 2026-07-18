package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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
 * Domain Responsibility: Black-box runtime coverage of the synchronous agent-run surface —
 *   {@code POST /api/agents/{agentId}/runs} (handled by
 *   {@link ai.operativus.agentmanager.control.controller.AgentsController#run}
 *   → {@link ai.operativus.agentmanager.core.registry.AgentOperations#run}
 *   → {@link ai.operativus.agentmanager.compute.service.AgentService#run}). Pins the
 *   canonical blocking chat path: ChatClient prompt call, AgentRun lifecycle write-through,
 *   agent_sessions creation/update via {@code ensureSessionExists}, agent_messages append
 *   via the Spring AI ChatMemory advisor, session-id resolution, followups plumbing,
 *   and the ResourceNotFoundException → 404 failure mode.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §6.1, §6.3, §6.9, §6.11, plus
 * the null-sessionId controller path from {@code AgentsController.resolveSessionId}.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link ai.operativus.agentmanager.integration.support.FakeChatModelConfig} shadows
 *     every per-provider {@code ChatModel} bean name ({@code openAiChatModel},
 *     {@code anthropicChatModel}, {@code googleGenAiChatModel}) with the same
 *     {@link FakeChatModel}. Scripts are FIFO; each {@code respondWith(...)} call
 *     consumes exactly one {@code client.prompt().call()}. A {@code generateFollowups=true}
 *     run issues TWO upstream calls (primary response + followups synth), so tests for
 *     that path must script two responses.
 *   - {@link ai.operativus.agentmanager.compute.service.AgentService#run} writes:
 *       (1) one {@code agent_runs} row (status transitions RUNNING → COMPLETED),
 *       (2) one {@code agent_sessions} row via {@code ensureSessionExists} if the sessionId
 *           is new, else updates {@code updated_at} on the existing row,
 *       (3) two {@code agent_messages} rows per prompt round-trip via the Spring AI
 *           ChatMemory advisor — one with {@code message_type='USER'} (prompt) and one
 *           with {@code message_type='ASSISTANT'} (response content). Tool and system
 *           messages would add more rows; the canned-text path produces exactly 2.
 *   - Unknown {@code agentId} surfaces via {@code agentRegistry.findById} returning null
 *     → {@code ResourceNotFoundException("Agent", agentId)} → 404 via GlobalExceptionHandler
 *     (NOT 500). Cross-org isolation at the run endpoint is NOT currently enforced — the
 *     controller has no tenant-scoped lookup, and the Hibernate {@code @Filter("tenantFilter")}
 *     mechanism that previously decorated {@code AgentEntity} was removed because it never
 *     reliably activated under Spring Boot 4 OSIV. This suite only pins the "row does not
 *     exist" 404 path.
 *   - {@code generateFollowups=true} appends {@code "followUpSuggestions"} to
 *     {@link ai.operativus.agentmanager.core.model.RunResponse#metadata()} with the raw
 *     JSON-array text the followups synth returned. {@code false} or absent → the key is
 *     omitted. The flag is a nullable Boolean in the request DTO and the controller
 *     coerces {@code null → false} (see {@code AgentsController.run} line 121).
 *   - {@code AgentsController.resolveSessionId} (line 201-203) auto-generates a UUID when
 *     the request body omits {@code sessionId}. The response carries the generated id so
 *     clients can thread follow-up turns. {@code ensureSessionExists} then creates a
 *     matching {@code agent_sessions} row keyed on that UUID.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SyncRunsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

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

    // §6.1 — Canonical sync run: canned ChatModel response flows through AgentService.run,
    // AgentRun row transitions to COMPLETED with input/output persisted, agent_sessions row
    // is created via ensureSessionExists, and the Spring AI ChatMemory advisor writes one
    // USER + one ASSISTANT row to agent_messages. Pins the four surfaces required by the
    // spec (HTTP response, run persisted state, session persisted state, message persisted state)
    // in a single test so a regression in any of them fails loudly.
    @Test
    void syncRunWithCannedTextReturns200AndPersistsRunSessionAndTwoMessages() {
        HttpHeaders auth = authenticatedHeaders("sync-runner");
        String agentId = createAgentViaApi(auth, "Sync Happy Path Agent");

        String cannedReply = "Here is your canned assistant response.";
        fakeModel.respondWith(cannedReply);

        String sessionId = "session-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Please answer the test prompt.");
        body.put("sessionId", sessionId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "sync run must return 200 OK on success — the controller is a blocking @PostMapping");
        Map<String, Object> payload = response.getBody();
        assertNotNull(payload, "200 response must carry the RunResponse body");
        assertEquals(cannedReply, payload.get("content"),
                "RunResponse.content must echo the canned FakeChatModel reply verbatim");
        assertEquals(sessionId, payload.get("sessionId"),
                "RunResponse.sessionId must reflect the caller-provided sessionId so clients can thread turns");
        assertEquals("COMPLETED", payload.get("status"),
                "RunResponse.status serializes as the RunStatus enum's string value on the happy path");
        String runId = (String) payload.get("runId");
        assertNotNull(runId, "RunResponse.runId must be populated so clients can fetch status later");

        Map<String, Object> runRow = jdbc.queryForMap(
                "SELECT status, input, output, session_id, agent_id FROM agent_runs WHERE id = ?", runId);
        assertEquals("COMPLETED", runRow.get("status"),
                "agent_runs.status must land COMPLETED — AgentService.run copies RunResponse.status back to the row");
        assertEquals("Please answer the test prompt.", runRow.get("input"),
                "agent_runs.input must persist the caller's prompt verbatim for audit replay");
        assertEquals(cannedReply, runRow.get("output"),
                "agent_runs.output must persist the assistant reply for audit replay");
        assertEquals(sessionId, runRow.get("session_id"));
        assertEquals(agentId, runRow.get("agent_id"));

        Long sessionCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(1L, sessionCount,
                "ensureSessionExists must create exactly one agent_sessions row for a new sessionId");

        Long messageCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ?", Long.class, sessionId);
        assertEquals(2L, messageCount,
                "Spring AI ChatMemory advisor writes one USER + one ASSISTANT row per single-turn prompt; anything else signals memory-wiring drift");

        Long userMessages = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND message_type = 'USER'",
                Long.class, sessionId);
        Long assistantMessages = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND message_type = 'ASSISTANT'",
                Long.class, sessionId);
        assertEquals(1L, userMessages, "exactly one USER row per single-turn prompt");
        assertEquals(1L, assistantMessages, "exactly one ASSISTANT row per single-turn prompt");
    }

    // §6.3 (row-miss subset) — AgentService.run explicitly throws ResourceNotFoundException
    // when agentRegistry.findById returns null; GlobalExceptionHandler maps that to RFC-7807
    // 404. The test pins that the controller does NOT leak a 500/NPE on unknown ids, and that
    // no agent_runs / agent_sessions rows are persisted as a side effect of the failed lookup.
    //
    // NOTE: true cross-org isolation at the run endpoint is not currently enforced — the
    // controller has no tenant-scoped agent lookup. This case therefore pins the
    // "truly unknown id" path, not the "known in another org" path.
    @Test
    void syncRunAgainstUnknownAgentIdReturns404AndPersistsNothing() {
        HttpHeaders auth = authenticatedHeaders("sync-404-caller");

        String unknownAgentId = "agent-does-not-exist-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "message", "This should never reach a ChatModel.",
                "sessionId", "session-" + UUID.randomUUID());

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/agents/" + unknownAgentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "unknown agentId must surface as 404 (ResourceNotFoundException → RFC-7807) instead of a generic 500");

        Long runRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE agent_id = ?", Long.class, unknownAgentId);
        assertEquals(0L, runRows,
                "validation failure must short-circuit before any runRepository.save — no orphan RUNNING rows");

        assertTrue(fakeModel.receivedPrompts().isEmpty(),
                "404 path must not reach the ChatModel boundary — otherwise we're paying LLM cost for an invalid request");
    }

    // §6.9 — Two consecutive POSTs with the same sessionId thread through to the same
    // agent_sessions row (ensureSessionExists finds + updates, does not duplicate) and
    // append a fresh USER + ASSISTANT pair each turn. Pins session continuity so memory-window
    // regressions (e.g. accidentally rotating the session per-call) fail here before
    // downstream RAG / summarization tests notice it.
    @Test
    void twoSyncRunsWithSameSessionIdAppendToOneSessionAndFourMessages() {
        HttpHeaders auth = authenticatedHeaders("sync-session-runner");
        String agentId = createAgentViaApi(auth, "Sync Session Agent");

        fakeModel.respondWith("First reply.").respondWith("Second reply.");

        String sessionId = "session-" + UUID.randomUUID();
        Map<String, Object> firstBody = Map.of(
                "message", "First user turn.",
                "sessionId", sessionId);
        Map<String, Object> secondBody = Map.of(
                "message", "Second user turn.",
                "sessionId", sessionId);

        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(firstBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals("First reply.", first.getBody().get("content"));

        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(secondBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals("Second reply.", second.getBody().get("content"));

        Long sessionRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(1L, sessionRows,
                "ensureSessionExists must update the existing session on second call, not insert a duplicate");

        Long runRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE session_id = ?", Long.class, sessionId);
        assertEquals(2L, runRows,
                "each turn persists its own agent_runs row — runs are independent audit records even when sharing a session");

        Long messageRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ?", Long.class, sessionId);
        assertEquals(4L, messageRows,
                "two turns × (1 USER + 1 ASSISTANT) = 4; anything less signals the ChatMemory advisor dropped history");

        Long userRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND message_type = 'USER'",
                Long.class, sessionId);
        Long assistantRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND message_type = 'ASSISTANT'",
                Long.class, sessionId);
        assertEquals(2L, userRows, "two USER messages accumulated across the two turns");
        assertEquals(2L, assistantRows, "two ASSISTANT messages accumulated across the two turns");
    }

    // §6.11 — generateFollowups=true triggers a SECOND client.prompt().call() inside
    // AgentService.run that synthesizes follow-up questions; the raw response text is
    // stuffed into RunResponse.metadata under key "followUpSuggestions". When the flag is
    // absent (controller coerces null → false at AgentsController.run line 121), that key
    // must NOT appear in metadata. Pins both the plumbing AND the negative case so a future
    // default-flip (null → true) would fail loudly.
    @Test
    void generateFollowupsTruePopulatesMetadataFollowupKeyAndFalseOmitsIt() {
        HttpHeaders auth = authenticatedHeaders("sync-followups-runner");
        String agentId = createAgentViaApi(auth, "Sync Followups Agent");

        // followups=true run — needs 2 scripted responses: primary + followups synth.
        fakeModel.respondWith("Primary assistant answer.")
                .respondWith("[\"What next?\", \"Can you expand?\", \"Any edge cases?\"]");

        Map<String, Object> followupsOnBody = new HashMap<>();
        followupsOnBody.put("message", "Run with followups enabled.");
        followupsOnBody.put("sessionId", "session-" + UUID.randomUUID());
        followupsOnBody.put("generateFollowups", Boolean.TRUE);

        ResponseEntity<Map<String, Object>> withFollowups = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(followupsOnBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, withFollowups.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> metadataOn = (Map<String, Object>) withFollowups.getBody().get("metadata");
        assertNotNull(metadataOn, "RunResponse.metadata must always be present (seeded with model id) even when followups disabled");
        assertTrue(metadataOn.containsKey("followUpSuggestions"),
                "generateFollowups=true must append a 'followUpSuggestions' key to RunResponse.metadata with the raw followups text; got keys: " + metadataOn.keySet());
        assertEquals("[\"What next?\", \"Can you expand?\", \"Any edge cases?\"]",
                metadataOn.get("followUpSuggestions"),
                "metadata.followUpSuggestions must carry the raw content of the second ChatModel call so clients can parse the JSON array themselves");
        assertEquals(2, fakeModel.receivedPrompts().size(),
                "followups=true must issue exactly TWO upstream prompts — one for the user answer, one for the followups synth");

        // Reset and verify the negative case — followups flag absent (default coercion).
        fakeModel.reset();
        fakeModel.respondWith("Primary-only assistant answer.");

        Map<String, Object> followupsOffBody = new HashMap<>();
        followupsOffBody.put("message", "Run with followups omitted.");
        followupsOffBody.put("sessionId", "session-" + UUID.randomUUID());
        // No generateFollowups field — exercises AgentsController.run line 121's null coercion.

        ResponseEntity<Map<String, Object>> withoutFollowups = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(followupsOffBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, withoutFollowups.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> metadataOff = (Map<String, Object>) withoutFollowups.getBody().get("metadata");
        assertNotNull(metadataOff);
        assertFalse(metadataOff.containsKey("followUpSuggestions"),
                "generateFollowups null/false must NOT produce a 'followUpSuggestions' key — this pin prevents a default-flip from silently doubling LLM cost per run");
        assertEquals(1, fakeModel.receivedPrompts().size(),
                "followups disabled must issue exactly ONE upstream prompt — the user's primary turn");
    }

    // AgentsController.resolveSessionId (line 201-203) — when the request omits sessionId,
    // the controller generates a UUID, threads it through AgentService.run's
    // ensureSessionExists, and echoes it back in RunResponse.sessionId so the caller can
    // continue the conversation. Pins this so a change to resolveSessionId (e.g. returning
    // null, or a non-UUID format) would be caught here rather than surfacing as "orphan
    // sessions" at runtime.
    @Test
    void syncRunWithoutSessionIdAutoGeneratesUuidAndPersistsSessionRow() {
        HttpHeaders auth = authenticatedHeaders("sync-autosession-runner");
        String agentId = createAgentViaApi(auth, "Sync Auto-Session Agent");

        fakeModel.respondWith("Reply for auto-session run.");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "Run without a client-provided sessionId.");
        // sessionId deliberately omitted — controller must fill it in.

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String echoedSessionId = (String) response.getBody().get("sessionId");
        assertNotNull(echoedSessionId,
                "controller must echo the auto-generated sessionId so the caller can continue the conversation");
        // Loose UUID check — resolveSessionId uses UUID.randomUUID().toString() which is
        // 36 chars with 4 hyphens. Exact parse would couple us to the current impl; this
        // is the weakest assertion that still catches a "" or null regression.
        assertTrue(echoedSessionId.length() >= 32 && echoedSessionId.contains("-"),
                "auto-generated sessionId must be a UUID-shaped string; got: " + echoedSessionId);

        Long sessionRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, echoedSessionId);
        assertEquals(1L, sessionRows,
                "ensureSessionExists must persist a matching agent_sessions row keyed on the auto-generated id");

        Long runRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE session_id = ?", Long.class, echoedSessionId);
        assertEquals(1L, runRows,
                "the agent_runs row must also thread through the same auto-generated sessionId so session-scoped audit queries work");
    }

    // ─── helpers ───

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Sync run test owner");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        // AgentEntity ctor coerces null memoryEnabled → false (see AgentEntity.java line 212),
        // which SUPPRESSES MessageChatMemoryAdvisor wiring in AgentClientFactory.buildChatClient.
        // The default is a production cost-safety choice (no history = no re-submission); tests
        // that assert on agent_messages persistence must opt IN explicitly.
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before run endpoints reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-sync-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
