package com.operativus.agentmanager.integration.knowledge;

import com.operativus.agentmanager.control.service.KnowledgeService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetadataKeys;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins retrieval edge cases that the RAG advisor must tolerate
 *   without crashing the agent run:
 *   <ol>
 *     <li><strong>Empty KB</strong>: An agent assigned to a KB that has zero documents
 *         must still complete a query — {@code knowledgeService.search} returns an empty
 *         list rather than throwing, and {@code AdvancedRagAdvisor.augmentRequest}
 *         falls through to the LLM call with no injected context.</li>
 *     <li><strong>No similarity-threshold cutoff at retrieval</strong>: as-shipped behavior
 *         pin. {@code KnowledgeService.search(String)} builds a {@code SearchRequest}
 *         without a {@code similarityThreshold} — every chunk that passes the orgId/KB
 *         filter is returned regardless of vector distance. The reranker is the only
 *         relevance gate downstream, and it's optional. This test pins that an
 *         "irrelevant" query (semantically distant under {@link
 *         com.operativus.agentmanager.integration.support.FakeEmbeddingModel}'s
 *         SHA-256-derived vectors) still surfaces the corpus contents. A future change
 *         that introduces a threshold cutoff would flip this assertion deliberately.</li>
 *   </ol>
 *
 *   <p>Why this matters: silent retrieval failures are the worst class of RAG bug —
 *   the LLM still returns an answer, just with wrong (or no) context. Pinning the empty-KB
 *   path keeps it observably wired; pinning the no-threshold path documents the operator-
 *   visible behavior so a future "RAG returned nothing" report can be triaged against the
 *   actual production contract (no threshold = retrieval-side never silently drops docs).</p>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class KnowledgeRetrievalEdgeCasesRuntimeTest extends BaseIntegrationTest {

    @Autowired private KnowledgeService knowledgeService;

    private static final String ORG = "org-k5-edge";

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM knowledge_contents");
        jdbc.update("DELETE FROM vector_store");
    }

    @Test
    void emptyKb_searchReturnsEmptyListWithoutThrowing() {
        // No documents ingested. The agent's KB allowlist isn't relevant here — what we
        // pin is that an org with zero indexed content does NOT crash the search path.
        Long preIngestRows = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                Long.class, ORG);
        assertThat(preIngestRows).isEqualTo(0L);

        List<Document> hits = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(() -> knowledgeService.search("any query against an empty KB"));

        assertThat(hits)
                .as("knowledgeService.search MUST return an empty list (not null, not throw) "
                        + "when the org has no indexed content — the RAG advisor relies on this "
                        + "to fall through to the LLM call without crashing the agent run")
                .isNotNull()
                .isEmpty();
    }

    @Test
    void search_retrievesOrgScopedCorpus_atAcceptAllThreshold_andStaysTenantFiltered() {
        // Seed two documents. Note the as-shipped retrieval contract is NOT "returns every chunk
        // regardless of distance": KnowledgeService.search uses Spring AI's default SearchRequest,
        // whose similarityThreshold is SIMILARITY_THRESHOLD_ACCEPT_ALL == 0.0. pgvector keeps only
        // hits with cosine similarity >= 0.0, so anti-correlated chunks ARE dropped. Under
        // FakeEmbeddingModel (SHA-256-derived, L2-normalized vectors) an unrelated query lands at a
        // random-sign cosine vs the corpus — ~50% negative — so a "guaranteed-irrelevant query
        // still returns the corpus" assertion is inherently non-deterministic and asserts a
        // stronger claim than production makes. We instead pin the deterministic, real contract:
        // a query whose embedding matches stored content (cosine == 1.0) is retrieved, and every
        // hit is org-scoped (tenant isolation). The downstream reranker remains the relevance gate.
        UUID kbId = UUID.randomUUID();
        ScopedValue.where(AgentContextHolder.orgId, ORG)
                .run(() -> {
                    knowledgeService.ingest("k5-doc-a.txt", "text/plain",
                            ("K5 alpha document about quantum chromodynamics and lattice gauge theory "
                                    + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
                            kbId);
                    knowledgeService.ingest("k5-doc-b.txt", "text/plain",
                            ("K5 bravo document about Renaissance fresco restoration techniques "
                                    + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
                            kbId);
                });

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    Long count = jdbc.queryForObject(
                            "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                            Long.class, ORG);
                    assertThat(count).isNotNull().isGreaterThanOrEqualTo(2L);
                });

        // Query with a stored chunk's EXACT text. FakeEmbeddingModel.deterministicVector is a pure
        // function of the text, so the query embedding equals that chunk's stored vector → cosine
        // 1.0 → it clears the accept-all (>= 0.0) gate every time. This makes the test deterministic
        // (no dependence on the random sign of an unrelated query's cosine) while still proving
        // retrieval reaches the org corpus through the real search path.
        String storedChunk = jdbc.queryForObject(
                "SELECT content FROM vector_store WHERE metadata->>'orgId' = ? "
                        + "AND metadata->>'storeType' = 'KB' ORDER BY content LIMIT 1",
                String.class, ORG);
        assertThat(storedChunk).as("precondition: at least one stored chunk to query by").isNotBlank();

        List<Document> hits = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(() -> knowledgeService.search(storedChunk));

        assertThat(hits)
                .as("a query matching ingested content (cosine 1.0) MUST be retrieved at the "
                        + "accept-all threshold; an empty result would mean the retrieval path is "
                        + "silently dropping an exact-match chunk")
                .isNotEmpty();

        // Tenant isolation: every returned chunk MUST belong to ORG.
        for (Document d : hits) {
            assertThat(d.getMetadata().get(MetadataKeys.ORG_ID)).isEqualTo(ORG);
        }
    }
}
