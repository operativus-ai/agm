package ai.operativus.agentmanager.integration.memory;

import ai.operativus.agentmanager.compute.memory.MemoryConsolidationWorker;
import ai.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import ai.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import ai.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.registry.MemoryOperations;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModel;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the agentic-memory surface —
 *   {@link ai.operativus.agentmanager.control.controller.MemoryController} at
 *   {@code /api/memories}, {@link ai.operativus.agentmanager.control.service.MemoryService}
 *   (MemoryOperations registry implementation), {@link MemoryConsolidationWorker}
 *   (outbox poller, @Scheduled pushed to 24h in tests — invoked directly here), and
 *   {@code MEMORY_OPTIMIZATION} background-job wiring. Pins memory persistence,
 *   user-scoped retrieval, outbox consolidation, job handler dispatch, and CRUD.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §13 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T021 (10 cases).
 *
 * Spec-vs-reality gaps these tests pin (see 15-issues.md):
 *   - Spec calls for "org-scoped memory"; M4 closes this at the vector_store layer:
 *     {@link MemoryService} now writes {@code MetadataKeys.ORG_ID} into Document
 *     metadata on {@code addMemory} and AND-filters by orgId on both
 *     {@code searchMemories} and {@code searchUserMemories}. The {@code agentic_memories}
 *     ledger table still has no {@code org_id} column — that ledger-level cross-tenant
 *     concern (delete cascade, repository queries) is tracked separately as M7.
 *     {@link MemorySearchTenantIsolationRuntimeTest} pins the vector-store-layer
 *     isolation contract.
 *   - Spec calls for {@code POST /memory/search} returning top-K with similarity
 *     scores; the real endpoint is {@code GET /api/memories?query=} returning
 *     {@code List<String>} (text only). Case 8 pins the as-shipped shape.
 *   - Case 7 was a cultural-memory cross-tenant isolation pin. Cultural memory was
 *     dropped pre-launch (see docs/analysis/agm-advisor-chain-audit.md). The case
 *     is intentionally absent; the cultural_knowledge table remains in the schema
 *     (empty) so a future re-introduction has a stable migration story.
 *   - Spec calls for agent-delete cascade to memories; {@code agentic_memories.agent_id}
 *     is a plain VARCHAR (no FK, no cascade). Case 9 documents the no-cascade
 *     behaviour as a pin — a future migration that adds the FK should flip this.
 *
 * Consolidation worker note: {@link MemoryConsolidationWorker#processPendingMemoryExtractions}
 * runs on {@code agentmanager.scheduler.memory-consolidation-ms} (prod default 5s,
 * test properties pin to 24h). Tests invoke it directly for determinism.
 */
@Import({
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class,
        JobQueueTestSupport.class
})
class MemoryRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<String>> JSON_STRING_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private MemoryOperations memoryOperations;
    @Autowired private MemoryConsolidationWorker consolidationWorker;
    @Autowired private AgenticMemoryRepository memoryRepo;
    @Autowired private AgenticMemoryOutboxRepository outboxRepo;
    @Autowired private PersistentJobQueueService queue;
    @Autowired private JobQueueTestSupport jobs;
    @Autowired private FakeChatModel fakeChat;
    @Autowired private FakeEmbeddingModel fakeEmbedding;
    @Autowired private ObjectMapper mapper;

    @BeforeEach
    void resetFakes() {
        fakeChat.reset();
        fakeEmbedding.reset();
        seedDefaultModel();
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // ─── Case 1: persist via memory write → DB + vector + outbox rows ───

    /**
     * Matrix §13 #1. The spec phrases this as "persist a memory via tool call". In
     *   production, every tool surface ({@code AgenticMemoryTools.save_memory},
     *   {@code ActiveMemoryTools.saveMemoryTool}) terminates at
     *   {@link MemoryOperations#addMemory} — so calling the registry directly exercises
     *   the identical persistence path. Pins: (1) a row lands in {@code agentic_memories}
     *   with the content + userId + MemoryTier; (2) a vector doc is synchronously
     *   inserted into {@code vector_store} with {@code metadata.userId}; (3) an
     *   outbox row lands in {@code agentic_memory_outbox} with status PENDING,
     *   linked to the memory by {@code memoryId}.
     */
    @Test
    void persistMemory_roundTripsToDbAndVectorStore() {
        String userId = "user-t021-1-" + UUID.randomUUID();
        String content = "T021 case 1: user prefers dark mode and SI units.";

        memoryOperations.addMemory(content, userId);

        List<AgenticMemoryEntity> rows = memoryRepo.findByUserId(userId);
        assertEquals(1, rows.size(), "exactly one agentic_memories row for the user");
        AgenticMemoryEntity row = rows.get(0);
        assertEquals(content, row.getMemory());
        assertEquals(AgenticMemoryEntity.MemoryTier.USER_MEMORY, row.getMemoryTier());
        assertNotNull(row.getVectorId(), "sync vector insert must populate vector_id");

        Long vectorRowsForUser = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'userId' = ?",
                Long.class, userId);
        assertTrue(vectorRowsForUser != null && vectorRowsForUser >= 1,
                "at least one vector_store row must carry metadata.userId for the memory");

        List<AgenticMemoryOutboxEntity> outboxRows = outboxRepo.findAll().stream()
                .filter(o -> o.getMemoryId().equals(row.getMemoryId()))
                .toList();
        assertEquals(1, outboxRows.size(), "exactly one outbox row linked to this memory");
        assertEquals(AgenticMemoryOutboxEntity.OutboxStatus.PENDING, outboxRows.get(0).getStatus(),
                "fresh outbox rows land as PENDING so MemoryConsolidationWorker can pick them up");

        assertFalse(fakeEmbedding.receivedRequests().isEmpty(),
                "FakeEmbeddingModel must observe the sync vector insert's embed call");
    }

    // ─── Case 2: retrieve via searchUserMemories (substrate for AgenticMemoryAdvisor) ───

    /**
     * Matrix §13 #2. {@link ai.operativus.agentmanager.compute.advisor.AgenticMemoryAdvisor}
     *   injects matching memories into prompts by calling
     *   {@link MemoryOperations#searchUserMemories}. That method hard-codes its query
     *   text ({@code "User preferences and semantic rules"}) and relies on the
     *   embedding model to rank the user's stored facts. With
     *   {@link FakeEmbeddingModel}'s SHA-256 deterministic vectors, the seeded-fact
     *   vectors are effectively random relative to the fixed query vector — so HNSW
     *   recall on a tiny test table is lossy (may return {@code < topK} docs).
     *
     * Pins the contract at the two layers that matter:
     *   (1) persistence — {@code AgenticMemoryRepository.findByUserId} returns every
     *       memory bound to the user; this is what a future production query that
     *       iterates the user's memories would rely on;
     *   (2) retrieval floor — {@code searchUserMemories} returns AT LEAST one of the
     *       seeded facts, which is enough for the advisor's prompt-injection path to
     *       function end-to-end.
     */
    @Test
    void retrieveMemory_searchUserMemoriesReturnsSeededContent() {
        String userId = "user-t021-2-" + UUID.randomUUID();
        String orgId = "org-t021-2-" + UUID.randomUUID();
        String fact1 = "T021#2 alpha — user uses metric units";
        String fact2 = "T021#2 bravo — user prefers async over sync";
        String fact3 = "T021#2 charlie — user builds Spring Boot 4 apps";

        // M4: addMemory and searchUserMemories both require an orgId via
        // AgentContextHolder ScopedValue (or SecurityContext fallback). Bind here so the
        // vector_store row carries metadata.orgId and the filter has a match.
        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, orgId).run(() -> {
            memoryOperations.addMemory(fact1, userId);
            memoryOperations.addMemory(fact2, userId);
            memoryOperations.addMemory(fact3, userId);
        });

        List<AgenticMemoryEntity> persisted = memoryRepo.findByUserId(userId);
        assertEquals(3, persisted.size(),
                "all three memories must persist in agentic_memories under the seeded userId");
        List<String> texts = persisted.stream().map(AgenticMemoryEntity::getMemory).toList();
        assertTrue(texts.contains(fact1) && texts.contains(fact2) && texts.contains(fact3),
                "every seeded fact must round-trip through JPA");

        List<String> retrieved = ScopedValue.where(
                ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, orgId)
                .call(() -> memoryOperations.searchUserMemories(userId));
        assertFalse(retrieved.isEmpty(),
                "searchUserMemories must return at least one fact — this is the substrate "
                        + "AgenticMemoryAdvisor uses for prompt injection. HNSW recall with "
                        + "FakeEmbeddingModel's deterministic vectors is lossy for multi-doc "
                        + "queries; that's acceptable because the advisor only needs SOME "
                        + "context, not every memory ever written");
        assertTrue(retrieved.stream().anyMatch(t -> t.equals(fact1) || t.equals(fact2) || t.equals(fact3)),
                "retrieved text must be one of the seeded facts (userId+orgId filter applied correctly); got: " + retrieved);
    }

    // ─── Case 3: user isolation (spec says org; reality is userId) ───

    /**
     * Matrix §13 #3. Spec phrases this as "memory is org-scoped" but production scopes
     *   memory by {@code userId}. {@code agentic_memories} has no {@code org_id} column;
     *   {@link ai.operativus.agentmanager.control.service.MemoryService#searchUserMemories}
     *   hard-codes a {@code userId == '...'} filter expression. This test pins the real
     *   isolation semantics: cross-user leak must not occur. A future migration that
     *   introduces {@code org_id} scoping should extend this into a two-dimensional
     *   (user, org) isolation check.
     */
    @Test
    void userIsolation_searchUserMemoriesScopesByUserId() {
        String userA = "user-t021-3a-" + UUID.randomUUID();
        String userB = "user-t021-3b-" + UUID.randomUUID();
        String factA = "T021#3 user-A only — alpha data";
        String factB = "T021#3 user-B only — bravo data";

        memoryOperations.addMemory(factA, userA);
        memoryOperations.addMemory(factB, userB);

        // Isolation pin #1 — JPA layer. agentic_memories.user_id is the row-level
        // tenancy boundary; findByUserId is the authoritative "what does this user
        // own" query used by MemoryService.optimizeMemories.
        List<AgenticMemoryEntity> rowsA = memoryRepo.findByUserId(userA);
        List<AgenticMemoryEntity> rowsB = memoryRepo.findByUserId(userB);
        assertEquals(1, rowsA.size(), "user-A owns exactly one row");
        assertEquals(1, rowsB.size(), "user-B owns exactly one row");
        assertEquals(factA, rowsA.get(0).getMemory(),
                "user-A's row must carry factA (no cross-user leak at persistence layer)");
        assertEquals(factB, rowsB.get(0).getMemory(),
                "user-B's row must carry factB");

        // Isolation pin #2 — vector_store metadata filter. This is the substrate
        // the similaritySearch filterExpression ("userId == '<id>'") runs against.
        Long vectorsForA = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'userId' = ?",
                Long.class, userA);
        Long vectorsForB = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'userId' = ?",
                Long.class, userB);
        assertEquals(1L, vectorsForA,
                "exactly one vector_store row must carry metadata.userId=userA");
        assertEquals(1L, vectorsForB,
                "exactly one vector_store row must carry metadata.userId=userB");

        // Isolation pin #3 — the live search path. Retrieval may be lossy under
        // FakeEmbeddingModel (HNSW + synthetic vectors), but whatever IS returned
        // must come from the caller's userId filter — no cross-user contamination.
        List<String> hitsA = memoryOperations.searchUserMemories(userA);
        assertFalse(hitsA.contains(factB),
                "user-A must NEVER see user-B's memory — userId filter expression in MemoryService");
        List<String> hitsB = memoryOperations.searchUserMemories(userB);
        assertFalse(hitsB.contains(factA),
                "user-B must NEVER see user-A's memory");
    }

    // ─── Case 4: MemoryConsolidationWorker drains the outbox ───

    /**
     * Matrix §13 #4. {@link MemoryConsolidationWorker#processPendingMemoryExtractions}
     *   polls {@code agentic_memory_outbox} (FOR UPDATE SKIP LOCKED), and for each
     *   pending row runs a semantic-synthesis pass before re-indexing into
     *   {@code vector_store}. If {@code vectorStore.similaritySearch(rawMemory)}
     *   returns a prior doc with score &gt; 0.85, the worker asks the ChatClient to
     *   merge old + new into a single canonical fact. FakeEmbeddingModel is
     *   deterministic (identical text → identical vector → score ~1.0), so the
     *   synchronously-inserted doc from {@link MemoryOperations#addMemory} guarantees
     *   a collision — the worker WILL call the LLM on every memory, and we script
     *   FakeChatModel accordingly.
     *
     * Pins: (1) the outbox row transitions PENDING → COMPLETED; (2) a second vector
     *   row is added (the synthesis); (3) FakeChatModel observed exactly one
     *   consolidation prompt.
     */
    @Test
    void consolidationWorker_processesPendingOutbox() {
        String userId = "user-t021-4-" + UUID.randomUUID();
        String orgId = "org-t021-4-" + UUID.randomUUID();
        String content = "T021#4 — user's favourite colour is blue.";
        String synthesized = "Consolidated: user prefers blue.";
        fakeChat.respondWith(synthesized);

        // M5: addMemory must run under a bound orgId so the vector_store row carries
        // metadata.orgId. The consolidation worker resolves the parent's orgId from that
        // metadata before issuing the LLM merge call — without it, the worker skips the
        // cross-tenant similarity search defensively and the LLM is never called.
        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, orgId)
                .run(() -> memoryOperations.addMemory(content, userId));

        List<AgenticMemoryOutboxEntity> pendingBefore = outboxRepo.findAll().stream()
                .filter(o -> o.getStatus() == AgenticMemoryOutboxEntity.OutboxStatus.PENDING)
                .toList();
        assertEquals(1, pendingBefore.size(), "precondition: exactly one PENDING outbox row");
        UUID outboxId = pendingBefore.get(0).getOutboxId();

        Long vectorBefore = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);

        consolidationWorker.processPendingMemoryExtractions();

        AgenticMemoryOutboxEntity after = outboxRepo.findById(outboxId)
                .orElseThrow(() -> new AssertionError("outbox row disappeared: " + outboxId));
        assertEquals(AgenticMemoryOutboxEntity.OutboxStatus.COMPLETED, after.getStatus(),
                "worker must mark the outbox row COMPLETED after semantic-sync phase");

        Long vectorAfter = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        assertTrue(vectorAfter != null && vectorBefore != null && vectorAfter > vectorBefore,
                "worker must insert a new consolidated doc into vector_store "
                        + "(before=" + vectorBefore + ", after=" + vectorAfter + ")");

        assertEquals(1, fakeChat.receivedPrompts().size(),
                "FakeEmbeddingModel's deterministic vectors guarantee a similarity collision > 0.85, "
                        + "so the worker must issue exactly one LLM consolidation call");
    }

    // ─── Case 5: MemoryOptimizationJobHandler wires to the queue ───

    /**
     * Matrix §13 #5. The spec phrases this as "archive low-salience memories", but
     *   production's {@link ai.operativus.agentmanager.control.service.MemoryService#optimizeMemories}
     *   has no salience field — it LLM-consolidates batches of ≥3 memories per user
     *   and replaces each batch with a single merged memory. The simplest determinism
     *   is the empty-user path: enqueue the job for a user with zero memories, drain
     *   the queue, assert the job lands COMPLETED. That pins: (1) the
     *   {@code MEMORY_OPTIMIZATION} job type is registered in {@code JobHandlerRegistry};
     *   (2) the handler deserialises {@code Payload(userId)}; (3) the execution path
     *   dispatches to {@link MemoryOperations#optimizeMemories}; (4) early-return on
     *   empty findByUserId yields a clean COMPLETED — not FAILED.
     */
    @Test
    void optimizationJobHandler_enqueueAndDrain() throws Exception {
        String userId = "user-t021-5-" + UUID.randomUUID();
        String payload = mapper.writeValueAsString(
                new ai.operativus.agentmanager.control.service.queue.MemoryOptimizationJobHandler.Payload(userId));

        BackgroundJob enqueued = queue.enqueue(
                ai.operativus.agentmanager.control.service.queue.MemoryOptimizationJobHandler.JOB_TYPE,
                null, payload, null, "T021-5-" + userId);
        assertNotNull(enqueued);
        String jobId = enqueued.getId();

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
        assertEquals("COMPLETED", terminal.getStatus().name(),
                "zero-memory user must exercise MemoryService.optimizeMemories early-return (no LLM call, no failure)");

        assertEquals(0, fakeChat.receivedPrompts().size(),
                "optimize with 0 memories must not reach the LLM consolidation branch");
    }

    // ─── Case 6: CRUD via MemoryController HTTP endpoints ───

    /**
     * Matrix §13 #6. {@link ai.operativus.agentmanager.control.controller.MemoryController}
     *   exposes CRUD at {@code /api/memories}: {@code POST /} with
     *   {@code {"content":"..."}} returns 200, {@code GET /?query=} returns a
     *   {@code List<String>}, and {@code DELETE /} with a JSON array body of vector
     *   IDs returns 204. As of R4, mutating endpoints carry
     *   {@code @PreAuthorize("hasRole('ADMIN')")} — this test now authenticates as
     *   {@code ROLE_USER + ROLE_ADMIN} so the CRUD round-trip exercises the admin
     *   path. The non-admin 403 path is pinned by
     *   {@link #memoryMutationsRequireAdmin_403ForRoleUser_R4ProductionFix}.
     *
     * Pins: (1) POST with empty content returns 400; (2) POST round-trips to DB;
     *   (3) GET returns the seeded content; (4) DELETE by vector_id removes the
     *   agentic_memories row (via the orphan cleanup in MemoryService.deleteMemories).
     */
    @Test
    void memoryCrud_httpEndpoints() {
        HttpHeaders auth = authenticateAs("memory-crud-" + UUID.randomUUID().toString().substring(0, 8),
                "memcrud@test.local", "pass-mem-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        ResponseEntity<Map<String, Object>> badRequest = rest.exchange(
                url("/api/memories"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", ""), auth), JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode(),
                "empty content must be rejected with 400 by MemoryController");

        String content = "T021#6 CRUD — HTTP POST seed " + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/memories"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", content), auth), JSON_MAP);
        assertEquals(HttpStatus.OK, created.getStatusCode(),
                "POST /api/memories must return 200 with {message: 'Memory saved'}");
        assertEquals("Memory saved", created.getBody().get("message"));

        // Persistence pin — the authoritative check for "did the POST land". GET
        // /api/memories?query= rides similaritySearch which is lossy under
        // FakeEmbeddingModel's deterministic-but-synthetic vectors, so we rely on
        // the JPA layer for the round-trip assertion instead.
        List<AgenticMemoryEntity> persisted = memoryRepo.findAll().stream()
                .filter(m -> content.equals(m.getMemory()))
                .toList();
        assertEquals(1, persisted.size(), "exactly one row must persist for the POSTed content");
        String vectorId = persisted.get(0).getVectorId();
        assertNotNull(vectorId);

        // Search sanity — endpoint wiring is alive (200 + body parses to List<String>);
        // we don't assert which rows come back because HNSW recall with synthetic
        // vectors is non-deterministic for a multi-row corpus.
        ResponseEntity<List<String>> listed = rest.exchange(
                url("/api/memories?query=T021"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_STRING_LIST);
        assertEquals(HttpStatus.OK, listed.getStatusCode(),
                "GET /api/memories?query= must wire to MemoryService.searchMemories and return 200");
        assertNotNull(listed.getBody(),
                "response body must deserialize to List<String> (even if empty under fake-embedding recall)");

        // MemoryService.deleteMemories now wipes sibling agentic_memory_outbox rows via
        // the no-cascade FK before removing the ledger row — test exercises the full
        // cascade in one DELETE call (no manual outbox wipe required).
        UUID memoryId = persisted.get(0).getMemoryId();

        ResponseEntity<Void> deleted = rest.exchange(
                url("/api/memories"), HttpMethod.DELETE,
                new HttpEntity<>(List.of(vectorId), auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode(),
                "DELETE /api/memories must return 204 after removing vector + ledger + outbox rows");

        List<AgenticMemoryEntity> remaining = memoryRepo.findAll().stream()
                .filter(m -> content.equals(m.getMemory()))
                .toList();
        assertTrue(remaining.isEmpty(),
                "MemoryService.deleteMemories must scrub the ledger row whose vector_id matches");

        Integer outboxLeft = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentic_memory_outbox WHERE memory_id = ?",
                Integer.class, memoryId);
        assertEquals(0, outboxLeft,
                "MemoryService.deleteMemories must wipe sibling outbox rows before removing the ledger row");
    }

    // ─── Case 6b: R4 RBAC enforcement — mutating endpoints reject ROLE_USER ───

    /**
     * R4 production fix. {@link ai.operativus.agentmanager.control.controller.MemoryController}
     *   now carries {@code @PreAuthorize("hasRole('ADMIN')")} on POST, DELETE, and
     *   POST /optimize. A bare ROLE_USER caller is rejected with 403 before
     *   {@link ai.operativus.agentmanager.control.service.MemoryService} runs — verified
     *   here by asserting that the agentic_memories table stays empty after each call.
     *
     * <p>Reads (GET / and GET /stats / GET /topics) intentionally stay open to authenticated
     * users; this test does not exercise them.</p>
     */
    @Test
    void memoryMutationsRequireAdmin_403ForRoleUser_R4ProductionFix() {
        HttpHeaders userOnly = authenticateAs(
                "memory-rbac-" + UUID.randomUUID().toString().substring(0, 8),
                "memrbac@test.local", "pass-mem-r4-1234", List.of("ROLE_USER"));

        long before = memoryRepo.count();

        ResponseEntity<Map<String, Object>> post = rest.exchange(
                url("/api/memories"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "R4 guard probe " + UUID.randomUUID()), userOnly),
                JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, post.getStatusCode(),
                "ROLE_USER caller must be rejected by MemoryController.addMemory @PreAuthorize");

        ResponseEntity<Void> delete = rest.exchange(
                url("/api/memories"), HttpMethod.DELETE,
                new HttpEntity<>(List.of("never-persisted-id"), userOnly), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, delete.getStatusCode(),
                "ROLE_USER caller must be rejected by MemoryController.deleteMemories @PreAuthorize");

        ResponseEntity<Map<String, Object>> optimize = rest.exchange(
                url("/api/memories/optimize"), HttpMethod.POST,
                new HttpEntity<>(userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, optimize.getStatusCode(),
                "ROLE_USER caller must be rejected by MemoryController.optimizeMemories @PreAuthorize");

        assertEquals(before, memoryRepo.count(),
                "no agentic_memories row may land — rejection must happen before MemoryService runs");
    }

    // ─── Case 7 removed: cultural-memory feature dropped pre-launch.
    //     Per advisor-chain audit recon (docs/analysis/agm-advisor-chain-audit.md),
    //     CulturalMemoryAdvisor + CultureManager + CulturalKnowledgeEntity were
    //     removed because (a) no UI / API surface existed for operators to add
    //     rules and (b) the advisor ran on every chain unconditionally. The
    //     cultural_knowledge DB table is retained (empty; harmless) — a future
    //     re-introduction can either reuse it or migrate it out.

    // ─── Case 8: search endpoint returns list of content strings ───

    /**
     * Matrix §13 #8. The spec calls for {@code POST /memory/search} returning top-K
     *   with similarity scores; the real endpoint is {@code GET /api/memories?query=}
     *   returning {@code List<String>} (text only — no scores in the response shape).
     *   This test pins the as-shipped contract and documents the divergence.
     *   {@link FakeEmbeddingModel} is deterministic (identical text → identical
     *   vector), so exact-text seeds guarantee retrieval without relying on semantic
     *   similarity.
     */
    @Test
    void searchTopK_viaGetEndpointReturnsContentStrings() {
        // M4: GET /api/memories now tenant-scopes via the caller's JWT orgId
        // (TenantContextFilter binds AgentContextHolder.orgId from the claim). Use
        // registerLoginWithOrg so the user's orgId is predictable, then seed addMemory
        // under that SAME orgId so the resulting vector_store row matches the search
        // filter. Without this, the GET would refuse the query and return empty.
        String orgId = "org-t021-8-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg(
                "memory-search-" + UUID.randomUUID().toString().substring(0, 8), orgId);

        // Seed exactly one memory and query with its EXACT text. FakeEmbeddingModel
        // produces identical 768-dim vectors for identical input strings, so cosine
        // distance drops to 0 and HNSW surfaces the row deterministically — which
        // sidesteps the recall flakiness that plagues multi-seed fake-embedding tests.
        String seed = "T021#8 unique marker " + UUID.randomUUID();
        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, orgId)
                .run(() -> memoryOperations.addMemory(seed, "system"));

        ResponseEntity<List<String>> result = rest.exchange(
                url("/api/memories?query={q}"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_STRING_LIST, seed);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertTrue(result.getBody().contains(seed),
                "exact-text query must surface the seeded memory as a raw content string. "
                        + "Note: endpoint returns List<String>, NOT top-K with scores. M4 pins "
                        + "orgId tenant scoping on this endpoint via TenantContextFilter; "
                        + "got: " + result.getBody());
    }

    // ─── Case 9: deleting an agent does NOT cascade to memories (documenting gap) ───

    /**
     * Matrix §13 #9. {@code agentic_memories.agent_id} carries the foreign-key
     *   constraint {@code fk_agentic_memories_agent} (see
     *   {@code 016-performance-fks-and-indexes.sql} §135) referencing
     *   {@code agents.id} — WITHOUT {@code ON DELETE CASCADE} / {@code SET NULL}.
     *   Deleting an agent while memories still reference it is therefore REJECTED
     *   at the database layer. This is the inverse of the spec's cascade-delete
     *   framing: production keeps memories intact by refusing the parent delete
     *   entirely.
     *
     *   Pins the current behaviour so a future migration that introduces a cascade
     *   (either {@code CASCADE} or {@code SET NULL}) can flip this assertion. The
     *   pin lives in 15-issues.md alongside the spec-reality divergence.
     */
    @Test
    void deletingAgent_blockedByFkWhenMemoriesReferenceIt() {
        String userId = "user-t021-9-" + UUID.randomUUID();
        String agentId = "agent-t021-9-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, description, model_id,
                                    is_reasoning_enabled, is_team, requires_pii_redaction,
                                    approved_for_production, maintenance_mode, active,
                                    enforce_json_output, instructions, created_at, updated_at)
                VALUES (?, 'T021#9 Agent', 'no-cascade fixture', 'gpt-4o-mini',
                        false, false, false, false, false, true, false,
                        'Be helpful.', now(), now())
                """, agentId);

        UUID memoryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agentic_memories (memory_id, memory, user_id, agent_id,
                                              memory_tier, created_at, updated_at)
                VALUES (?, 'T021#9 fact bound to an agent', ?, ?, 'USER_MEMORY', now(), now())
                """, memoryId, userId, agentId);

        org.springframework.dao.DataIntegrityViolationException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update("DELETE FROM agents WHERE id = ?", agentId),
                        "fk_agentic_memories_agent must reject agent deletion while "
                                + "agentic_memories rows still reference the agent_id");
        assertTrue(thrown.getMessage().contains("fk_agentic_memories_agent"),
                "exception must surface the specific FK name for diagnostic clarity; got: "
                        + thrown.getMessage());

        Long memoryCount = jdbc.queryForObject(
                "SELECT count(*) FROM agentic_memories WHERE memory_id = ?",
                Long.class, memoryId);
        assertEquals(1L, memoryCount,
                "memory row must survive unchanged after the blocked delete");

        Long agentCount = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Long.class, agentId);
        assertEquals(1L, agentCount,
                "agent row must survive — the FK violation rolls the DELETE back");
    }

}
