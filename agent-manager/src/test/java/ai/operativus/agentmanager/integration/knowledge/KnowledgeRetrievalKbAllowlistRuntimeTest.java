package ai.operativus.agentmanager.integration.knowledge;

import ai.operativus.agentmanager.control.service.KnowledgeService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.MetadataKeys;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the agent-KB-allowlist contract for vector retrieval.
 *   {@code AgentService.run} binds {@code AgentContextHolder.allowedKnowledgeBaseIds} to
 *   the agent's {@code def.knowledgeBaseIds()} via {@code ScopedValue.where(...)}.
 *   {@code AgentSecurityFilters.buildVectorFilter()} consumes that binding to AND the
 *   {@code IN(knowledgeBaseId, …)} filter into the orgId filter, so retrieval is scoped to
 *   the agent's assigned KBs. This test pins:
 *   <ol>
 *     <li>Agent assigned only to KB-A → retrieves chunks from KB-A only, KB-B in the same
 *         org is invisible (K4 production fix).</li>
 *     <li>Agent assigned to both KB-A and KB-B → multi-KB merge happy path; both KBs'
 *         chunks are candidates.</li>
 *     <li>ScopedValue unbound / empty → org-only filter (legacy behavior preserved for
 *         system-background callers that don't bind the allowlist).</li>
 *   </ol>
 *
 *   <p><strong>K4 production fix shipped in this PR</strong>: previously,
 *   {@code AgentClientFactory:493} built a {@code SearchRequest} with a
 *   {@code knowledge_base_id IN (...)} filter but {@code AdvancedRagAdvisor:142} passed
 *   only {@code finalSearch.getQuery()} to {@code knowledgeService.search(String)}, which
 *   rebuilt an orgId-only filter via the old {@code buildVectorFilter}. An agent's
 *   configured KB allowlist was silently ignored at retrieval — cross-KB leakage within
 *   an org was possible. The fix consumes
 *   {@link AgentContextHolder#getAllowedKnowledgeBaseIds()} directly in
 *   {@code buildVectorFilter} so all callers of {@code knowledgeService.search} honor the
 *   allowlist.</p>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class KnowledgeRetrievalKbAllowlistRuntimeTest extends BaseIntegrationTest {

    @Autowired private KnowledgeService knowledgeService;

    private static final String ORG = "org-k4-allowlist";

    private UUID kbA;
    private UUID kbB;
    private String alphaText;
    private String bravoText;

    @BeforeEach
    void seedTwoKbsWithDistinctContent() {
        jdbc.update("DELETE FROM knowledge_contents");
        jdbc.update("DELETE FROM vector_store");

        kbA = UUID.randomUUID();
        kbB = UUID.randomUUID();
        alphaText = "K4 alpha document body — unique text for KB-A " + UUID.randomUUID();
        bravoText = "K4 bravo document body — unique text for KB-B " + UUID.randomUUID();

        ingestInOrgAndKb(kbA, "k4-alpha.txt", alphaText);
        ingestInOrgAndKb(kbB, "k4-bravo.txt", bravoText);

        // Wait for both VT ingests to land their chunks; otherwise the subsequent searches
        // race the splitter and may return stale-zero results.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    Long count = jdbc.queryForObject(
                            "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                            Long.class, ORG);
                    assertThat(count).isNotNull().isGreaterThanOrEqualTo(2L);
                });
    }

    @Test
    void searchWithSingleKbAllowlist_returnsOnlyAssignedKbContent_K4ProductionFix() {
        List<Document> hits = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .where(AgentContextHolder.allowedKnowledgeBaseIds, List.of(kbA.toString()))
                .call(() -> knowledgeService.search(alphaText));

        assertThat(hits).as("KB-A allowlist must surface at least the alpha chunks").isNotEmpty();

        for (Document d : hits) {
            String kbId = String.valueOf(d.getMetadata().get(MetadataKeys.KNOWLEDGE_BASE_ID));
            assertEquals(kbA.toString(), kbId,
                    "K4 fix: when allowedKnowledgeBaseIds=[KB-A], retrieval must NOT surface "
                            + "chunks tagged with any other knowledge_base_id. A KB-B hit here "
                            + "means AgentSecurityFilters.buildVectorFilter regressed to "
                            + "org-only — the agent's KB allowlist was silently ignored.");
        }
    }

    @Test
    void searchWithBothKbsInAllowlist_mergesCandidatesFromBoth() {
        List<Document> hitsForAlpha = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .where(AgentContextHolder.allowedKnowledgeBaseIds,
                        List.of(kbA.toString(), kbB.toString()))
                .call(() -> knowledgeService.search(alphaText));

        List<Document> hitsForBravo = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .where(AgentContextHolder.allowedKnowledgeBaseIds,
                        List.of(kbA.toString(), kbB.toString()))
                .call(() -> knowledgeService.search(bravoText));

        assertThat(hitsForAlpha.stream()
                .map(d -> String.valueOf(d.getMetadata().get(MetadataKeys.KNOWLEDGE_BASE_ID))))
                .as("alpha-query against [KB-A, KB-B] allowlist must include KB-A chunks")
                .contains(kbA.toString());

        assertThat(hitsForBravo.stream()
                .map(d -> String.valueOf(d.getMetadata().get(MetadataKeys.KNOWLEDGE_BASE_ID))))
                .as("bravo-query against [KB-A, KB-B] allowlist must include KB-B chunks — "
                        + "the IN filter is OR-equivalent across the listed KBs")
                .contains(kbB.toString());

        // The combined merge must NOT exclude either KB. Sanity-check by union:
        java.util.Set<String> allKbsInHits = new java.util.HashSet<>();
        hitsForAlpha.forEach(d -> allKbsInHits.add(
                String.valueOf(d.getMetadata().get(MetadataKeys.KNOWLEDGE_BASE_ID))));
        hitsForBravo.forEach(d -> allKbsInHits.add(
                String.valueOf(d.getMetadata().get(MetadataKeys.KNOWLEDGE_BASE_ID))));
        assertThat(allKbsInHits)
                .as("union of both queries' hits must cover both allowlisted KBs — a missing "
                        + "KB here would indicate the IN clause is constructing as exact-match "
                        + "or single-arg")
                .contains(kbA.toString(), kbB.toString());
    }

    @Test
    void searchWithEmptyAllowlist_fallsThroughToOrgOnlyFilter() {
        // "No allowlist bound" is the system-background path — no agent context,
        // retrieval scoped only by orgId (matches the legacy behavior).
        List<Document> hits = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .where(AgentContextHolder.allowedKnowledgeBaseIds, Collections.emptyList())
                .call(() -> knowledgeService.search(alphaText));

        assertThat(hits)
                .as("empty allowlist must NOT lock out retrieval — it falls through to "
                        + "org-only filtering. Legacy callers and the scraper agent rely on this.")
                .isNotEmpty();
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private void ingestInOrgAndKb(UUID kbId, String filename, String body) {
        // KnowledgeService.ingest tags each chunk's metadata with knowledgeBaseId,
        // orgId, sourceId, etc. The ScopedValue binding around the call sets orgId on
        // the calling thread; AgentContextSnapshot.capture() inside ingest() rebinds
        // it on the VT before the chunk metadata write.
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ScopedValue.where(AgentContextHolder.orgId, ORG)
                .run(() -> knowledgeService.ingest(filename, "text/plain", payload, kbId));
    }
}
