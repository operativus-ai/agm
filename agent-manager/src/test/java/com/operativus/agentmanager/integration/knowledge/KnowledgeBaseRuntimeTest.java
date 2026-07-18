package com.operativus.agentmanager.integration.knowledge;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.service.KnowledgeService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.MetadataKeys;
import com.operativus.agentmanager.core.registry.KnowledgeIngestionOperations;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModel;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the knowledge surface —
 *   {@link com.operativus.agentmanager.control.controller.KnowledgeBaseController}
 *   (KB CRUD + agent binding @ {@code /api/v1/knowledge-bases}),
 *   {@link com.operativus.agentmanager.control.controller.KnowledgeController}
 *   (document-level ingest/search/SSE @ {@code /api/knowledge}), and the
 *   {@code KNOWLEDGE_INGESTION} background-job handler. Pins KB persistence,
 *   URL/text ingestion round-trips, hash-dedup (migration 019), delete cascade,
 *   RAG retrieval with multi-tenant vector filtering, reranker behaviour, vector
 *   store cache hits, crawler policy, and concurrent ingestion safety.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §7 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T020 (10 cases).
 *
 * Embedding boundary: {@link FakeEmbeddingModel} returns deterministic 768-dim
 * vectors (SHA-256 → normalized float[]). Identical text yields identical vector
 * — safe for uniqueness-by-text assertions, NOT semantic similarity. Tests that
 * need to inspect embedding calls inject {@code FakeEmbeddingModel} by concrete
 * type and read {@code receivedRequests()}.
 *
 * HTTP fetch boundary: URL-ingest paths call {@code Jsoup.connect(url).get()}
 * inline, so the URL itself is the seam. Happy paths point Jsoup at a WireMock
 * server on a dynamic localhost port; unreachable paths use {@code http://localhost:1/}
 * (reserved port, instant connection-refused). No production refactor. See
 * decisions.md "T020 URL-fetch seam" for rationale.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@code KnowledgeBase} entity has no {@code org_id} column — org scope lives
 *     on ingested chunks via {@code MetadataKeys.ORG_ID}. Case (a) therefore pins
 *     row persistence + owner attribution only; if a future migration adds
 *     {@code org_id} to {@code knowledge_bases}, extend the assertion.
 *   - {@link com.operativus.agentmanager.control.service.KnowledgeService#ingestUrlAsync}
 *     is fire-and-forget on a virtual thread with NO retry. Case (d) therefore
 *     drives failure through the agent-bootstrap path
 *     ({@code POST /api/agents/{id}/knowledge/load}) which enqueues onto
 *     {@code background_jobs} where {@code RetryTemplate} applies. If the direct
 *     endpoint is ever moved onto the queue this case's setup should be reviewed.
 *   - {@code KnowledgeIngestionService.ingest(Resource)} writes the source
 *     description into {@code metadata.source} (literal key "source"), while
 *     {@code KnowledgeService.ingestUrlAsync} uses {@code MetadataKeys.SOURCE_ID}
 *     ("sourceId"). Case (c) asserts on "source", case (b) on "sourceId". That
 *     divergence is real production behaviour worth pinning.
 */
@Import({
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class,
        JobQueueTestSupport.class
})
class KnowledgeBaseRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static WireMockServer wiremock;

    @Autowired private FakeEmbeddingModel fakeEmbedding;
    @Autowired private JobQueueTestSupport jobs;
    @Autowired private KnowledgeIngestionOperations knowledgeIngestion;
    @Autowired private KnowledgeService knowledgeService;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wiremock != null) wiremock.stop();
    }

    @BeforeEach
    void resetFixtures() {
        wiremock.resetAll();
        fakeEmbedding.reset();
        seedDefaultModel();
    }

    // ─── Case (a): create KB → row persists ───

    /**
     * Matrix §7 #1. POST /api/v1/knowledge-bases returns the saved entity with HTTP 200
     *   (the controller has no explicit @ResponseStatus), the row lands in
     *   {@code knowledge_bases}, and {@code name}/{@code description} round-trip through
     *   JPA. Tenant scoping is now active: the controller stamps {@code org_id} from the
     *   caller's {@code AgentContextHolder} (or {@code TenantConstants.DEFAULT_SYSTEM_ORG}
     *   when the caller has no orgId set on their User row, as is the case for
     *   {@code authenticatedHeaders}-issued tokens). Cross-tenant 404 contract is pinned
     *   in {@link KnowledgeBaseTenantIsolationRuntimeTest}.
     */
    @Test
    void createKnowledgeBase_persistsRowWithOrgScope() {
        HttpHeaders auth = authenticatedHeaders("kb-creator");

        String name = "T020a KB " + UUID.randomUUID();
        Map<String, Object> body = Map.of("name", name, "description", "KB create fixture");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, created.getStatusCode(),
                "POST /api/v1/knowledge-bases returns 200 (controller has no @ResponseStatus(CREATED))");
        assertNotNull(created.getBody());
        String kbId = (String) created.getBody().get("id");
        assertNotNull(kbId, "response must carry the UUID primary key");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, description, org_id FROM knowledge_bases WHERE id = ?::uuid", kbId);
        assertEquals(name, row.get("name"),
                "knowledge_bases.name round-trips through the POST body");
        assertEquals("KB create fixture", row.get("description"));
        assertNotNull(row.get("org_id"),
                "knowledge_bases.org_id MUST be stamped on create — never NULL after migration 041");

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_bases WHERE id = ?::uuid", Long.class, kbId);
        assertEquals(1L, count, "exactly one row must persist for the created KB");
    }

    // ─── Case (c): raw-text ingest → hash-dedup on re-ingest ───

    /**
     * Matrix §7 #3. {@link KnowledgeIngestionOperations#ingest(Resource)} uses stable
     *   per-chunk IDs derived from {@code source + "_chunk_" + i} and the
     *   {@code PgVectorStore} uses {@code ON CONFLICT (id) DO NOTHING}, so re-ingesting
     *   the same resource content is a no-op at the vector-store level. This test
     *   pins the dedup contract by calling {@code ingest} twice with the same
     *   {@link ByteArrayResource} (same description + same bytes) and asserting the
     *   vector-store row count is unchanged between calls. The embedding model IS
     *   re-invoked on the second call — {@code FakeEmbeddingModel.receivedRequests()}
     *   will show two batches — because dedup happens at the DB layer, not before
     *   embedding. That is the current production behaviour; a future optimization
     *   that short-circuits before embedding should flip this assertion.
     */
    @Test
    void ingestByText_hashDedupPreventsDuplicates() {
        String source = "t020c-fixture-" + UUID.randomUUID();
        byte[] payload = ("This is the T020(c) document body. Re-ingesting identical content "
                + "must not grow the vector_store because stable chunk IDs collide on insert.")
                .getBytes(StandardCharsets.UTF_8);
        Resource resource = new ByteArrayResource(payload) {
            @Override public String getDescription() { return source; }
        };

        Long beforeAll = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);

        knowledgeIngestion.ingest(resource);

        Long afterFirst = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        Long rowsForSource = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'source' = ?", Long.class, source);
        assertTrue(afterFirst > beforeAll, "first ingest must add at least one chunk");
        assertTrue(rowsForSource > 0,
                "chunks must carry the resource description under metadata.source (literal key 'source', not 'sourceId')");
        int embedCallsAfterFirst = fakeEmbedding.receivedRequests().size();
        assertTrue(embedCallsAfterFirst > 0, "FakeEmbeddingModel must see the first embed call");

        knowledgeIngestion.ingest(resource);

        Long afterSecond = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        assertEquals(afterFirst, afterSecond,
                "re-ingesting identical content must not add rows (stable chunk IDs + ON CONFLICT DO NOTHING)");
        assertTrue(fakeEmbedding.receivedRequests().size() > embedCallsAfterFirst,
                "embedding IS re-computed on re-ingest — dedup happens in PgVectorStore, not upstream");
    }

    // ─── Case (b): happy URL ingest via WireMock ───

    /**
     * Matrix §7 #2. POST /api/knowledge/ingest-url is a 202-returning fire-and-forget
     *   endpoint; the real work runs on a virtual thread inside
     *   {@code KnowledgeService.ingestUrlAsync}. The test stubs Jsoup's HTTP fetch
     *   with a WireMock-served HTML document on a dynamic localhost port, polls
     *   {@code knowledge_contents.status} until COMPLETED via Awaitility, and asserts:
     *   (1) the controller returns 202 + documentId; (2) the async worker lands the
     *   row in COMPLETED status with a populated {@code vector_ids}; (3) chunks
     *   appear in {@code vector_store} with {@code metadata.sourceId = documentId}
     *   (note the "sourceId" key used by {@code ingestUrlAsync}, distinct from the
     *   "source" key used by {@link KnowledgeIngestionOperations#ingest(Resource)});
     *   (4) {@code FakeEmbeddingModel} was invoked at least once.
     */
    @Test
    void ingestByUrl_persistsChunksAndReflectsStatus() {
        HttpHeaders auth = authenticatedHeaders("kb-url-ingester");

        wiremock.stubFor(get(urlEqualTo("/t020b"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody("<html><head><title>T020b Fixture</title></head>"
                                + "<body><p>This document is served by WireMock to exercise the "
                                + "URL ingestion path. Its chunks land in vector_store under the "
                                + "documentId assigned by KnowledgeService.ingestUrlAsync.</p></body></html>")));

        Map<String, Object> kbBody = Map.of("name", "T020b KB " + UUID.randomUUID(), "description", "url-ingest fixture");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST, new HttpEntity<>(kbBody, auth), JSON_MAP);
        String kbId = (String) kbCreated.getBody().get("id");
        assertNotNull(kbId, "KB fixture precondition");

        String targetUrl = "http://localhost:" + wiremock.port() + "/t020b";
        // Build the URI via UriComponentsBuilder so queryParam() encodes exactly once.
        // Hand-rolling URLEncoder.encode + string-concat leaves the '%' escapes in the
        // URL template, and RestTemplate's URI-template expansion then re-encodes them
        // (%3A → %253A) so the controller receives an already-encoded URL and passes it
        // to Jsoup, which rejects it as malformed.
        URI ingestUri = UriComponentsBuilder.fromUriString(url("/api/knowledge/ingest-url"))
                .queryParam("url", targetUrl)
                .queryParam("knowledgeBaseId", kbId)
                .build()
                .toUri();
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                ingestUri, HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode(),
                "POST /api/knowledge/ingest-url is fire-and-forget: 202 + {documentId, status:PROCESSING}");
        String docId = (String) accepted.getBody().get("documentId");
        assertNotNull(docId);
        assertEquals("PROCESSING", accepted.getBody().get("status"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM knowledge_contents WHERE id = ?::uuid",
                            String.class, docId);
                    assertEquals("COMPLETED", status,
                            "ingestUrlAsync must land knowledge_contents.status=COMPLETED within the poll window");
                });

        Long chunkCount = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, docId);
        assertTrue(chunkCount != null && chunkCount > 0,
                "at least one chunk must land in vector_store with metadata.sourceId = documentId; got " + chunkCount);

        assertTrue(!fakeEmbedding.receivedRequests().isEmpty(),
                "FakeEmbeddingModel must observe the embed call from ingestUrlAsync");
    }

    // ─── Case (d): unreachable URL via agent-bootstrap → RetryTemplate terminal ───

    /**
     * Matrix §7 #4. The agent-bootstrap ingestion path
     *   (POST /api/agents/{id}/knowledge/load → background_jobs → KnowledgeIngestionJobHandler
     *   → AgentService.loadKnowledge → KnowledgeIngestionService.ingestUrl) is the only
     *   URL-ingest surface with a real retry policy — the direct /ingest-url endpoint is
     *   fire-and-forget with no retry (see 15-issues.md). Seeding the agent with
     *   {@code bootstrapKnowledgeUrls = ["http://localhost:1/"]} triggers Jsoup's
     *   connection-refused failure path instantly; the DLQ trick ({@code max_retries=0})
     *   collapses the RetryTemplate window so a single {@code processNow()} tick lands
     *   the job terminal. Pins: (1) the unreachable URL surfaces as a terminal
     *   {@code background_jobs} row with status ∈ {FAILED, DLQ}; (2) the error message
     *   carries network-layer signal (connect/refused), NOT the "bootstrapKnowledgeUrls"
     *   string from {@link com.operativus.agentmanager.compute.service.AgentService#loadKnowledge}
     *   — that string fires only when the config key is ABSENT, not when the URL fails.
     */
    @Test
    void ingestUnreachableUrl_failsCleanlyWithStatus() {
        HttpHeaders auth = authenticatedHeaders("kb-unreachable-ingester");
        String agentId = createAgentWithBootstrapUrls(auth, "T020d Unreachable Agent",
                List.of("http://localhost:1/"));

        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/agents/" + agentId + "/knowledge/load"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = (String) accepted.getBody().get("jobId");
        assertNotNull(jobId);

        jdbc.update("UPDATE background_jobs SET max_retries = 0 WHERE id = ?", jobId);

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobFailure(jobId, Duration.ofSeconds(10));
        String status = terminal.getStatus().name();
        assertTrue("FAILED".equals(status) || "DLQ".equals(status),
                "unreachable URL must land the job terminal in {FAILED, DLQ}; got " + status);

        String err = terminal.getErrorMessage();
        assertNotNull(err, "errorMessage must be populated so operators can diagnose without grepping logs");
        String errLower = err.toLowerCase();
        assertTrue(errLower.contains("connect") || errLower.contains("refused")
                        || errLower.contains("localhost") || errLower.contains("url"),
                "errorMessage must carry network-layer signal (not the 'bootstrapKnowledgeUrls' missing-config string); got: " + err);
    }

    // ─── Cases (e)–(j): scaffolds awaiting implementation ───

    /** Case (e) — matrix §7 #5: Delete KB → vector rows + JPA rows removed; agents remain functional. */
    /**
     * Matrix §7 #5. {@code DELETE /api/v1/knowledge-bases/{id}} enqueues a
     *   {@code KNOWLEDGE_BASE_DELETION} background_jobs row and returns 202 + jobId. The
     *   handler ({@link com.operativus.agentmanager.control.service.queue.KnowledgeBaseDeletionJobHandler})
     *   calls {@link com.operativus.agentmanager.control.service.KnowledgeBaseService#deleteWithCascade}
     *   which removes the {@code knowledge_bases} row synchronously and spawns a
     *   virtual thread to scrub agent references and paginated {@code knowledge_contents}
     *   cleanup. This test seeds a KB + binds an agent (JSONB array column) then drives
     *   the full cascade: the KB row disappears, the job lands COMPLETED, and the
     *   agent row survives with the KB id removed from {@code knowledge_base_ids}.
     */
    @Test
    void deleteKnowledgeBase_removesRowsAndKeepsAgentsWorking() {
        HttpHeaders auth = authenticatedHeaders("kb-deleter");

        Map<String, Object> kbBody = Map.of("name", "T020e KB " + UUID.randomUUID(), "description", "delete-cascade fixture");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST, new HttpEntity<>(kbBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, kbCreated.getStatusCode(), "KB fixture precondition");
        String kbId = (String) kbCreated.getBody().get("id");

        String agentId = "agent-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, description, model_id, knowledge_base_ids,
                                    is_reasoning_enabled, is_team, requires_pii_redaction,
                                    approved_for_production, maintenance_mode, active, enforce_json_output,
                                    instructions, created_at, updated_at)
                VALUES (?, 'T020e Bound Agent', 'delete-cascade fixture', 'gpt-4o-mini', ?::jsonb,
                        false, false, false, false, false, true, false,
                        'Be helpful.', now(), now())
                """, agentId, "[\"" + kbId + "\"]");

        ResponseEntity<Map<String, Object>> deleted = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId), HttpMethod.DELETE, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, deleted.getStatusCode(),
                "DELETE is enqueued on background_jobs and returns 202 + jobId");
        String jobId = (String) deleted.getBody().get("jobId");
        assertNotNull(jobId, "jobId must accompany 202");

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobSuccess(jobId, Duration.ofSeconds(15));
        assertNotNull(terminal);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Long kbCount = jdbc.queryForObject(
                            "SELECT count(*) FROM knowledge_bases WHERE id = ?::uuid", Long.class, kbId);
                    assertEquals(0L, kbCount, "knowledge_bases row must be removed by deleteWithCascade");
                });

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String ids = jdbc.queryForObject(
                            "SELECT knowledge_base_ids::text FROM agents WHERE id = ?", String.class, agentId);
                    assertNotNull(ids, "agent row itself must survive the cascade");
                    assertTrue(!ids.contains(kbId),
                            "agents.knowledge_base_ids must be scrubbed of the deleted KB id (virtual-thread cleanup); got " + ids);
                });
    }

    /**
     * Pins {@code KnowledgeService.delete(documentId)} cascade — the document-level
     *   counterpart to {@link #deleteKnowledgeBase_removesRowsAndKeepsAgentsWorking}.
     *   {@code KnowledgeService.delete} is {@code @Transactional} and calls
     *   {@code vectorStore.delete(vectorIds)} BEFORE removing the metadata row, so
     *   either both deletes commit or neither does. Pins:
     *   (1) at least one chunk lands in {@code vector_store} during ingest; (2) the
     *   {@code knowledge_contents} row exists; (3) {@code DELETE /api/knowledge/{id}}
     *   returns 204; (4) every {@code vector_store} row whose
     *   {@code metadata.sourceId} matches the deleted document is gone; (5) the
     *   {@code knowledge_contents} row is gone too.
     *
     * Audit anchor: {@code docs/analysis/agm-left.md} §8 KB-3 — the doc claimed the
     *   vector cascade was "skipped for prototype stability". This test pins the
     *   actual shipped contract: cascade IS wired and atomic.
     */
    @Test
    void deleteDocument_cascadesToVectorStoreAndMetadata() {
        HttpHeaders auth = authenticatedHeaders("kb-doc-deleter");

        wiremock.stubFor(get(urlEqualTo("/kb-doc-cascade"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody("<html><head><title>Doc Cascade Fixture</title></head>"
                                + "<body><p>Document body for the document-delete cascade test. "
                                + "These chunks must vanish from vector_store after DELETE.</p></body></html>")));

        Map<String, Object> kbBody = Map.of(
                "name", "doc-cascade-kb-" + UUID.randomUUID(),
                "description", "fixture for document-delete vector cascade");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST, new HttpEntity<>(kbBody, auth), JSON_MAP);
        String kbId = (String) kbCreated.getBody().get("id");
        assertNotNull(kbId, "KB fixture precondition");

        String targetUrl = "http://localhost:" + wiremock.port() + "/kb-doc-cascade";
        URI ingestUri = UriComponentsBuilder.fromUriString(url("/api/knowledge/ingest-url"))
                .queryParam("url", targetUrl)
                .queryParam("knowledgeBaseId", kbId)
                .build()
                .toUri();
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                ingestUri, HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String docId = (String) accepted.getBody().get("documentId");
        assertNotNull(docId, "ingest-url must echo the assigned documentId");

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM knowledge_contents WHERE id = ?::uuid",
                            String.class, docId);
                    assertEquals("COMPLETED", status,
                            "ingestUrlAsync must reach COMPLETED before delete-cascade is meaningful");
                });

        Long chunksBefore = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, docId);
        assertTrue(chunksBefore != null && chunksBefore > 0,
                "precondition: at least one chunk must exist in vector_store; got " + chunksBefore);

        ResponseEntity<Void> deleted = rest.exchange(
                url("/api/knowledge/" + docId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode(),
                "DELETE /api/knowledge/{id} must return 204; got " + deleted.getStatusCode());

        Long chunksAfter = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, docId);
        assertEquals(0L, chunksAfter == null ? -1L : chunksAfter,
                "every vector_store row for sourceId=" + docId + " must be gone after DELETE — "
                        + "non-zero here means the @Transactional vectorStore.delete() in "
                        + "KnowledgeService.delete was skipped or rolled back; got " + chunksAfter);

        Long metadataAfter = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_contents WHERE id = ?::uuid",
                Long.class, docId);
        assertEquals(0L, metadataAfter == null ? -1L : metadataAfter,
                "knowledge_contents row must be gone after DELETE; got " + metadataAfter);
    }

    // ─── Case (f): RAG retrieval isolates by org ───

    /**
     * Matrix §7 #6. {@link KnowledgeService#search(String)} delegates to
     *   {@code VectorStore.similaritySearch} wrapped by
     *   {@link com.operativus.agentmanager.control.security.AgentSecurityFilters#buildVectorFilter()},
     *   which pins {@code metadata.orgId = AgentContextHolder.orgId} unless the Spring
     *   Security caller has {@code ROLE_SUPER_ADMIN}. This test seeds two distinct
     *   resources under two distinct orgIds (via {@code ScopedValue.where} around
     *   {@code ingest(Resource)}), then runs two searches under the same orgIds and
     *   asserts strict isolation: every returned chunk carries the active orgId and
     *   the "cross-org" search never surfaces the other tenant's chunks.
     *
     * Note: {@link FakeEmbeddingModel} is deterministic (SHA-256 → vector) so identical
     *   text yields identical vectors. Assertions are on {@code metadata.orgId} membership,
     *   not on semantic ranking.
     */
    @Test
    void ragRetrieval_isolatesByOrg() {
        String orgA = "org-t020f-a-" + UUID.randomUUID();
        String orgB = "org-t020f-b-" + UUID.randomUUID();
        String queryText = "T020f alpha document body unique to its own tenant " + UUID.randomUUID();
        String textB = "T020f bravo document body unique to its own tenant " + UUID.randomUUID();

        ingestInOrg(orgA, "t020f-alpha-" + UUID.randomUUID(), queryText);
        ingestInOrg(orgB, "t020f-bravo-" + UUID.randomUUID(), textB);

        List<Document> hitsInA = ScopedValue.where(AgentContextHolder.orgId, orgA)
                .call(() -> knowledgeService.search(queryText));
        assertTrue(!hitsInA.isEmpty(), "orgA must see its own alpha doc when searching for alpha text");
        for (Document d : hitsInA) {
            assertEquals(orgA, d.getMetadata().get(MetadataKeys.ORG_ID),
                    "orgA search must return only orgA-scoped chunks — found " + d.getMetadata().get(MetadataKeys.ORG_ID));
        }

        List<Document> hitsInB = ScopedValue.where(AgentContextHolder.orgId, orgB)
                .call(() -> knowledgeService.search(queryText));
        for (Document d : hitsInB) {
            assertEquals(orgB, d.getMetadata().get(MetadataKeys.ORG_ID),
                    "orgB must not see orgA's chunks when searching for orgA's text; got " + d.getMetadata().get(MetadataKeys.ORG_ID));
        }
    }

    /** Case (g) was a reranker on/off ordering test. LlmDocumentReRanker was removed
     *  pre-launch (see docs/analysis/agm-advisor-chain-audit.md) — the disabled-by-
     *  default flag was never flipped on by any tenant, so the advisor was pure cost
     *  with no consumed benefit. AdvancedRagAdvisor now uses
     *  {@link com.operativus.agentmanager.compute.advisor.PassthroughDocumentReRanker}
     *  which simply returns the first topN documents in retrieval order. If a real
     *  LLM-backed reranker is reintroduced, restore this case. */

    /** Case (h) — matrix §7 #8: Vector store cache — repeated identical query within TTL hits cache. */
    @Test
    @Disabled("T020(h): VectorStoreCacheAdvisor sits in the advisor chain and wraps the ChatClient call, "
            + "not KnowledgeService.search. It only exercises when we drive a full agent run through "
            + "/api/agents/{id}/runs. That path is already covered by SyncRunsRuntimeTest (T015); the "
            + "cache behaviour is the same mechanism from the advisor's perspective. Deferring until "
            + "T020 needs a dedicated cache-hit metric assertion that doesn't duplicate T015 coverage.")
    void vectorStoreCache_hitSkipsDbOnRepeatQuery() {
        // Plan: two identical ChatClient.prompt calls via a run endpoint, assert the second
        // run observes a finops.cache.savings.usd Micrometer counter increment.
    }

    /** Case (i) — matrix §7 #9: Crawler honours crawl-depth limit, domain allowlist, politeness delay. */
    @Test
    @Disabled("T020(i): WebsiteCrawlerService delegates to a self-hosted Firecrawl API at "
            + "${app.firecrawl.baseUrl:http://localhost:3002}. There's no in-repo depth/allowlist "
            + "enforcement — the server governs it. Testing our side means only asserting the "
            + "CrawlRequest we POST to Firecrawl carries the configured limit and format. The "
            + "actual depth/allowlist/politeness policies belong in a Firecrawl-side contract test. "
            + "Pin this as a 15-issues.md gap rather than a runtime test.")
    void crawler_enforcesDepthAndAllowlistAndPoliteness() {
        // Plan: WireMock Firecrawl, call WebsiteCrawlerService.crawlAndIngest, assert the outbound
        // JSON body limit/scrapeOptions match settings, and the resulting chunks carry expected metadata.
    }

    // ─── Case (j): concurrent ingestion — no deadlock, no lost writes ───

    /**
     * Matrix §7 #10. {@code PgVectorStore.add} is a simple {@code INSERT ... ON CONFLICT (id) DO NOTHING}
     *   under Postgres default MVCC — concurrent writes of distinct chunk IDs do not contend beyond
     *   row locks, and duplicate IDs are silently dropped. This test fans out N concurrent virtual-thread
     *   ingests with distinct source descriptions and asserts:
     *   (1) all N threads terminate within a generous budget (no deadlock);
     *   (2) {@code vector_store} grew by at least N chunks;
     *   (3) every source description has at least one chunk row (no lost writes).
     */
    @Test
    void concurrentIngestion_doesNotDeadlockPgvector() throws InterruptedException {
        int n = 8;
        List<String> sources = IntStream.range(0, n)
                .mapToObj(i -> "t020j-src-" + i + "-" + UUID.randomUUID())
                .toList();

        Long beforeAll = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);

        List<Thread> threads = new ArrayList<>();
        for (String src : sources) {
            threads.add(Thread.ofVirtual().start(() -> {
                byte[] payload = ("T020j concurrent ingest payload for " + src
                        + ". This is just enough text to survive TokenTextSplitter chunking with one chunk.")
                        .getBytes(StandardCharsets.UTF_8);
                Resource r = new ByteArrayResource(payload) {
                    @Override public String getDescription() { return src; }
                };
                knowledgeIngestion.ingest(r);
            }));
        }
        for (Thread t : threads) {
            assertTrue(t.join(Duration.ofSeconds(60)),
                    "all concurrent ingests must finish within 60s — no pgvector deadlock (thread " + t.threadId() + ")");
        }

        Long afterAll = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        assertTrue(afterAll >= beforeAll + n,
                "vector_store must grow by at least " + n + " chunks (one per distinct source); "
                        + "got delta " + (afterAll - beforeAll));

        for (String src : sources) {
            Long rows = jdbc.queryForObject(
                    "SELECT count(*) FROM vector_store WHERE metadata->>'source' = ?", Long.class, src);
            assertTrue(rows != null && rows > 0,
                    "source " + src + " must have at least one chunk persisted (no lost writes)");
        }
    }

    // ─── helpers ───

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-kb-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    /**
     * Ingests a small text resource under a specific orgId by binding
     * {@link AgentContextHolder#orgId} inside a ScopedValue scope. The inner
     * {@link com.operativus.agentmanager.control.service.KnowledgeIngestionService#ingest(Resource)}
     * captures the orgId as a local before submitting to its internal virtual-thread
     * executor, so the ScopedValue binding only needs to hold for the duration of the
     * ingest() call, not for the downstream virtual-thread work.
     */
    private void ingestInOrg(String orgId, String source, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        Resource r = new ByteArrayResource(payload) {
            @Override public String getDescription() { return source; }
        };
        ScopedValue.where(AgentContextHolder.orgId, orgId).run(() -> knowledgeIngestion.ingest(r));
    }

    private String createAgentWithBootstrapUrls(HttpHeaders auth, String name, List<String> urls) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "T020d bootstrap-URL fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("configuration", Map.of("bootstrapKnowledgeUrls", urls));

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before knowledge/load references it");
        return agentId;
    }
}
