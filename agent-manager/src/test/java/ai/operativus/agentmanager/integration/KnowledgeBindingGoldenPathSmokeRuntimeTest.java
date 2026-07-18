package ai.operativus.agentmanager.integration;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: <strong>Golden-path smoke for the knowledge-base wiring on
 *   the agent-run path.</strong> Third smoke in the family, alongside
 *   {@link GoldenPathSmokeRuntimeTest} (single-agent) and
 *   {@link TeamOrchestrationGoldenPathSmokeRuntimeTest} (SEQUENTIAL team).
 *
 *   <p>Composes the KB-aware single-agent journey end-to-end via HTTP:
 *   <ol>
 *     <li>Register + login admin user</li>
 *     <li>Create a knowledge base via {@code POST /api/v1/knowledge-bases}</li>
 *     <li>Verify the new KB surfaces in {@code GET /api/v1/knowledge-bases}</li>
 *     <li>Create an agent with {@code knowledgeBaseIds=[kbId]} at create time</li>
 *     <li>Verify the agent detail round-trips the KB binding</li>
 *     <li>Run the agent — pin that the {@code AdvancedRagAdvisor} handles an
 *         empty KB gracefully (KB exists, no content ingested) and the run
 *         completes successfully</li>
 *   </ol>
 *
 *   <p>The key behavioural pin is step 6 (the run): if the {@code AdvancedRagAdvisor}
 *   in the advisor chain throws on an empty KB — null retrieval, no vectors, missing
 *   embedding model context — the run path fails and the smoke flags it. This is the
 *   "operator creates a KB but hasn't ingested content yet" scenario, which is the
 *   default state for every brand-new KB. Without this pin, a regression that breaks
 *   the empty-KB path passes the existing KB content tests (which all pre-ingest
 *   data) but breaks the moment a real user creates a KB and runs their agent.
 *
 *   <p>What this smoke does NOT pin: actual RAG retrieval behavior (vector search,
 *   chunk hydration, reranker, citation surfaces). Those need ingested content and
 *   live in the dedicated {@code KnowledgeBaseRuntimeTest} + RAG-specific tests.
 *
 * State: Stateless. Explicit {@code truncateDatabase()} in {@code @BeforeEach} makes
 *   the test JVM-order-independent (Liquibase seeds a default agent that would
 *   otherwise contaminate fresh-tenant assertions).
 */
@Import({
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class
})
public class KnowledgeBindingGoldenPathSmokeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_REF =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAP_REF =
            new ParameterizedTypeReference<>() {};

    private static final String MODEL_ID = "gpt-4o-mini";

    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void truncateAndSeedFreshState() {
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
    void operatorCreatesKbBindsToAgentAndRunsItSuccessfullyOnEmptyKb() {
        // ── Step 1: register + login (admin needed for agent create).
        HttpHeaders auth = authenticateAs(
                "kb-smoke",
                "kb-smoke@test.local",
                "kb-smoke-pass-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        // ── Step 2: create knowledge base. KnowledgeBaseController returns 200, not 201
        // (no explicit @ResponseStatus on the create handler — pinned by KnowledgeBaseRuntimeTest).
        String kbName = "kb-smoke-" + UUID.randomUUID();
        Map<String, Object> kbBody = Map.of(
                "name", kbName,
                "description", "Knowledge-binding smoke fixture");
        ResponseEntity<Map<String, Object>> kbCreate = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST,
                new HttpEntity<>(kbBody, auth), MAP_REF);
        assertEquals(HttpStatus.OK, kbCreate.getStatusCode(),
                "step 2: KB create must return 200 (controller has no @ResponseStatus(CREATED)); "
                        + "got " + kbCreate.getStatusCode() + " body=" + kbCreate.getBody());
        String kbId = (String) kbCreate.getBody().get("id");
        assertNotNull(kbId, "step 2: KB create response must carry id");

        // ── Step 3: read-after-write — KB appears in the list endpoint.
        ResponseEntity<List<Map<String, Object>>> kbList = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.GET,
                new HttpEntity<>(auth), LIST_OF_MAP_REF);
        assertEquals(HttpStatus.OK, kbList.getStatusCode(),
                "step 3: KB list must return 200");
        assertTrue(kbList.getBody().stream().anyMatch(row -> kbId.equals(row.get("id"))),
                "step 3: KB just created must appear in the list; got "
                        + kbList.getBody().size() + " rows, none matching id=" + kbId);

        // ── Step 4: create an agent that references the KB at create time. This pins the
        // schema integration: AgentDefinition.knowledgeBaseIds round-trips through the
        // controller, through JPA, and back out on detail fetch.
        String agentId = "kb-smoke-agent-" + UUID.randomUUID();
        Map<String, Object> agentBody = new HashMap<>();
        agentBody.put("agentId", agentId);
        agentBody.put("name", "KB Smoke Agent");
        agentBody.put("description", "KB binding smoke fixture");
        agentBody.put("instructions", "Reply briefly.");
        agentBody.put("model", MODEL_ID);
        agentBody.put("isReasoningEnabled", false);
        agentBody.put("isTeam", false);
        agentBody.put("requiresPiiRedaction", false);
        agentBody.put("approvedForProduction", false);
        agentBody.put("maintenanceMode", false);
        agentBody.put("active", true);
        agentBody.put("enforceJsonOutput", false);
        agentBody.put("memoryEnabled", false);
        agentBody.put("addHistoryToMessages", true);
        agentBody.put("knowledgeBaseIds", List.of(kbId));

        ResponseEntity<Map<String, Object>> agentCreate = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST,
                new HttpEntity<>(agentBody, auth), MAP_REF);
        assertEquals(HttpStatus.CREATED, agentCreate.getStatusCode(),
                "step 4: agent create with knowledgeBaseIds must return 201; got "
                        + agentCreate.getStatusCode() + " body=" + agentCreate.getBody());

        // ── Step 5: detail fetch round-trips the KB binding. Pins that knowledgeBaseIds
        // is not silently dropped during create-then-fetch.
        ResponseEntity<Map<String, Object>> agentDetail = rest.exchange(
                url("/api/agents/" + agentId), HttpMethod.GET,
                new HttpEntity<>(auth), MAP_REF);
        assertEquals(HttpStatus.OK, agentDetail.getStatusCode(),
                "step 5: agent detail must return 200 for the just-created agent");
        @SuppressWarnings("unchecked")
        List<String> persistedKbIds = (List<String>) agentDetail.getBody().get("knowledgeBaseIds");
        assertNotNull(persistedKbIds,
                "step 5: agent detail must include knowledgeBaseIds field (even if just []). "
                        + "Got body keys: " + agentDetail.getBody().keySet());
        assertTrue(persistedKbIds.contains(kbId),
                "step 5: agent detail's knowledgeBaseIds must include the KB just bound; "
                        + "got " + persistedKbIds);

        // ── Step 6: run the agent. This is the heart of the smoke — the
        // AdvancedRagAdvisor is in the chain because knowledgeBaseIds is non-empty
        // (see AgentClientFactory line ~512). On an EMPTY KB (no ingested content),
        // the advisor must:
        //   - not throw when the vector-store search returns zero docs
        //   - not throw when the embedding model is the FakeEmbeddingModel
        //   - allow the underlying ChatModel call to fire so the run completes
        // A regression that fails any of these (e.g., NPE on null retrieval result,
        // hard-fail when vector-store has no docs for the KB) breaks every new-tenant
        // first-run experience — which is exactly what a smoke catches.
        String scriptedReply = "kb-smoke reply " + UUID.randomUUID();
        fakeChatModel.respondWith(scriptedReply);

        String sessionId = "kb-smoke-session-" + UUID.randomUUID();
        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "knowledge-bound run probe");
        runBody.put("sessionId", sessionId);

        ResponseEntity<Map<String, Object>> runResp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"), HttpMethod.POST,
                new HttpEntity<>(runBody, auth), MAP_REF);
        assertEquals(HttpStatus.OK, runResp.getStatusCode(),
                "step 6: agent run with bound-but-empty KB must return 200; got "
                        + runResp.getStatusCode() + " body=" + runResp.getBody()
                        + ". A non-200 here means the AdvancedRagAdvisor is throwing on the "
                        + "empty-KB path — that breaks every fresh-tenant first-run experience.");

        Map<String, Object> runOut = runResp.getBody();
        assertNotNull(runOut, "step 6: run body must not be null");
        assertEquals(scriptedReply, runOut.get("content"),
                "step 6: run content must equal the scripted FakeChatModel reply. "
                        + "A mismatch means an advisor in the chain is rewriting the response — "
                        + "most likely the RAG advisor mutating output despite zero retrievals.");
        assertNotNull(runOut.get("runId"), "step 6: run must return a runId");
    }
}
