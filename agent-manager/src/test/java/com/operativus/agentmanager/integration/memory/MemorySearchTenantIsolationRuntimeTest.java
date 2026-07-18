package com.operativus.agentmanager.integration.memory;

import com.operativus.agentmanager.control.service.MemoryService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetadataKeys;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins the cross-tenant isolation contract for
 *   {@link MemoryService#searchMemories(String)} and
 *   {@link MemoryService#searchUserMemories(String)} — the two semantic-search entry
 *   points into {@code vector_store} from the agentic-memory surface.
 *
 *   <p><strong>M4 production fix shipped in this PR.</strong> Pre-fix:
 *   <ul>
 *     <li>{@code MemoryService.searchMemories(query)} built
 *         {@code SearchRequest.builder().query(query).topK(10).build()} with NO
 *         filterExpression — every authenticated caller saw every memory across every
 *         org/userId that semantically matched. The HTTP endpoint
 *         {@code GET /api/memories} has no admin gate, so any logged-in user could
 *         enumerate every other tenant's memories.</li>
 *     <li>{@code MemoryService.searchUserMemories(userId)} filtered only on
 *         {@code userId == '<id>'}. Two orgs that share a common userId (e.g.,
 *         {@code "admin"}, {@code "alice"}) cross-leaked: org A's
 *         {@link com.operativus.agentmanager.compute.advisor.AgenticMemoryAdvisor}
 *         injection saw org B's same-userId rules.</li>
 *     <li>{@code MemoryService.addMemory} wrote only {@code Map.of("userId", …)} into
 *         the {@link org.springframework.ai.document.Document} metadata — even if the
 *         search filter had existed, no row had an {@code orgId} to match against.</li>
 *   </ul>
 *
 *   <p>Fix in this PR:
 *   <ol>
 *     <li>{@code addMemory} now writes {@link MetadataKeys#ORG_ID} into the Document
 *         metadata, sourced from {@code AgentContextHolder.getOrgId()} → SecurityContext
 *         {@code UserDetailsImpl.getOrgId()} chain.</li>
 *     <li>{@code searchMemories} now AND-filters by {@code orgId == <resolved orgId>}.
 *         When no orgId resolves, it refuses the query (returns empty) rather than
 *         falling through to "match everything".</li>
 *     <li>{@code searchUserMemories} now AND-combines {@code orgId == …} with the
 *         existing {@code userId == …} filter.</li>
 *   </ol>
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>searchMemories tenant isolation</strong>: org A's search must NOT
 *         surface org B's memories even when the content is semantically identical.</li>
 *     <li><strong>searchUserMemories tenant isolation</strong>: when the SAME userId
 *         exists in two orgs, the search must NOT cross-tenant-leak.</li>
 *     <li><strong>addMemory persists orgId metadata</strong>: the document metadata
 *         row in {@code vector_store} must carry {@link MetadataKeys#ORG_ID} for any
 *         filter to match.</li>
 *     <li><strong>searchMemories with unresolved orgId returns empty</strong>:
 *         defensive contract — refuse rather than leak universally.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class MemorySearchTenantIsolationRuntimeTest extends BaseIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private VectorStore vectorStore;

    private static final String ORG_A = "org-m4-alpha";
    private static final String ORG_B = "org-m4-bravo";

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agentic_memory_outbox");
        jdbc.update("DELETE FROM agentic_memories");
        jdbc.update("DELETE FROM vector_store");
    }

    @Test
    void searchMemories_orgAQuery_doesNotReturnOrgBMemories_M4ProductionFix() {
        String sharedTopic = "m4-shared-topic-" + UUID.randomUUID();
        String orgAContent = "org A memory body — " + sharedTopic + " — private to A";
        String orgBContent = "org B memory body — " + sharedTopic + " — private to B";

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory(orgAContent, "user-a"));
        ScopedValue.where(AgentContextHolder.orgId, ORG_B)
                .run(() -> memoryService.addMemory(orgBContent, "user-b"));

        List<String> orgAHits = ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .call(() -> memoryService.searchMemories(sharedTopic));

        assertThat(orgAHits)
                .as("M4 fix: searchMemories from org A must NOT surface any org B memory "
                        + "even when content is semantically identical. A leak here means the "
                        + "orgId filter regressed or addMemory stopped writing orgId metadata.")
                .doesNotContain(orgBContent)
                .allMatch(text -> !text.contains("private to B"));
    }

    @Test
    void searchUserMemories_sameUserIdAcrossOrgs_isolatesByOrg_M4ProductionFix() {
        // Both orgs have a userId "alice" — a realistic collision for any tenant
        // that uses email-localpart or first-name principals. Pre-fix the userId-only
        // filter let org A's AgenticMemoryAdvisor pull org B's alice rules.
        String sharedUserId = "alice";
        String orgARule = "org A alice rule — " + UUID.randomUUID();
        String orgBRule = "org B alice rule — " + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory(orgARule, sharedUserId));
        ScopedValue.where(AgentContextHolder.orgId, ORG_B)
                .run(() -> memoryService.addMemory(orgBRule, sharedUserId));

        List<String> orgAHits = ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .call(() -> memoryService.searchUserMemories(sharedUserId));

        assertThat(orgAHits)
                .as("M4 fix: searchUserMemories from org A must NOT surface org B's same-userId "
                        + "rules. A leak here means the orgId filter is missing from the "
                        + "FilterExpression — every two orgs sharing a userId would cross-leak.")
                .doesNotContain(orgBRule);
    }

    @Test
    void addMemory_writesOrgIdIntoVectorStoreMetadata() {
        String content = "M4 metadata probe " + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory(content, "metadata-probe-user"));

        Long withOrg = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                Long.class, ORG_A);
        assertThat(withOrg)
                .as("addMemory must write orgId into vector_store metadata. A zero count "
                        + "here means the Document metadata regressed to userId-only — the "
                        + "search filter has nothing to match against and would always return "
                        + "empty post-M4.")
                .isNotNull()
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void searchMemories_doesNotReturnKbDocuments_StoreTypeFilter() {
        // Memory + KB share `public.vector_store`. Pre-fix, MemoryService.searchMemories
        // filtered by orgId only — same-org KB chunks ingested via KnowledgeService
        // surfaced as memory hits because both row classes carried the same orgId tag.
        // Fix: addMemory writes storeType=MEMORY, KnowledgeService writes storeType=KB,
        // searchMemories ANDs storeType=MEMORY into the filter. This test inserts a
        // KB-shaped row directly via the VectorStore (bypassing KnowledgeService) so
        // the assertion isolates the filter behaviour from the ingestion pipeline.
        String topic = "m24-kb-bleed-topic-" + UUID.randomUUID();
        String memoryContent = "org A memory body — " + topic + " — should appear";
        String kbContent = "org A KB chunk body — " + topic + " — must NOT appear";

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory(memoryContent, "user-a"));

        // Hand-write a KB chunk to the same org's vector store. Mirrors what
        // KnowledgeService.ingest does on the post-fix path.
        Map<String, Object> kbMetadata = new HashMap<>();
        kbMetadata.put(MetadataKeys.ORG_ID, ORG_A);
        kbMetadata.put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_KB);
        kbMetadata.put(MetadataKeys.KNOWLEDGE_BASE_ID, UUID.randomUUID().toString());
        vectorStore.add(List.of(new Document(UUID.randomUUID().toString(), kbContent, kbMetadata)));

        // Confirm both rows landed in the shared vector_store with the expected
        // discriminator before exercising the filter. JDBC asserts isolate the
        // ingestion contract from any similarity-search noise from FakeEmbeddingModel.
        Long memoryRows = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE content = ? "
                + "AND metadata->>'storeType' = 'MEMORY' AND metadata->>'orgId' = ?",
                Long.class, memoryContent, ORG_A);
        assertThat(memoryRows)
                .as("Fix #24 setup: MemoryService.addMemory must persist with storeType=MEMORY")
                .isEqualTo(1L);
        Long kbRows = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE content = ? "
                + "AND metadata->>'storeType' = 'KB' AND metadata->>'orgId' = ?",
                Long.class, kbContent, ORG_A);
        assertThat(kbRows)
                .as("Fix #24 setup: KB-shaped row must persist with storeType=KB")
                .isEqualTo(1L);

        List<String> orgAHits = ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .call(() -> memoryService.searchMemories(topic));

        // The contract under test is the ABSENCE of KB content from a memory search,
        // mirroring the assertion shape used by the sibling cross-tenant tests. We do
        // not assert positive presence of memoryContent — FakeEmbeddingModel's
        // deterministic hash-based vectors do not guarantee that a topK=10 cutoff
        // surfaces the memory row above the similarity threshold. The pre-fix shape
        // would surface the KB row here (storeType filter absent); a non-empty list
        // containing kbContent is the leak this test guards against.
        assertThat(orgAHits)
                .as("Fix #24: memory search must NOT return KB-typed rows from the shared "
                        + "vector_store even within the same org. A leak here means the "
                        + "storeType=MEMORY predicate regressed from the searchMemories filter.")
                .doesNotContain(kbContent)
                .allMatch(text -> !text.contains("must NOT appear"));
    }

    @Test
    void addMemory_resolvesUserIdFromAgentContextHolder_NotSystemRuntime() {
        // Fix #25: POST /api/memories used to attribute every write to userId="SYSTEM_RUNTIME"
        // because MemoryController called the no-userId overload, which hardcoded the
        // string. The per-user vector filter (designed to prevent same-org cross-user
        // enumeration) collapsed because every row was tagged SYSTEM_RUNTIME. Post-fix
        // the no-userId overload resolves the caller's userId from AgentContextHolder or
        // the SecurityContext principal.
        String boundUserId = "u-fix25-" + UUID.randomUUID();
        String content = "fix25 user attribution probe " + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .where(AgentContextHolder.userId, boundUserId)
                .run(() -> memoryService.addMemory(content));

        // The vector_store row must carry the bound userId. A row tagged SYSTEM_RUNTIME
        // would mean the no-userId overload regressed.
        Long boundCount = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE content = ? AND metadata->>'userId' = ?",
                Long.class, content, boundUserId);
        assertThat(boundCount)
                .as("Fix #25: addMemory(content) must attribute the row to the bound userId, "
                        + "not the hardcoded SYSTEM_RUNTIME literal. A zero here means the "
                        + "regression — the no-userId overload re-introduced SYSTEM_RUNTIME.")
                .isNotNull()
                .isEqualTo(1L);

        Long systemRuntimeCount = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE content = ? AND metadata->>'userId' = 'SYSTEM_RUNTIME'",
                Long.class, content);
        assertThat(systemRuntimeCount)
                .as("No memory row may carry the SYSTEM_RUNTIME literal post-fix. A non-zero "
                        + "count here means addMemory still hardcodes the legacy attribution.")
                .isEqualTo(0L);
    }

    @Test
    void searchMemories_withoutResolvableOrgId_returnsEmptyNotEverything() {
        // Seed two orgs with a shared semantic topic.
        String topic = "m4-no-context-topic-" + UUID.randomUUID();
        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory("org A " + topic, "user-a"));
        ScopedValue.where(AgentContextHolder.orgId, ORG_B)
                .run(() -> memoryService.addMemory("org B " + topic, "user-b"));

        // Call WITHOUT binding AgentContextHolder.orgId and WITHOUT a SecurityContext
        // principal — the defensive contract is "refuse, return empty" rather than
        // "no filter, return everything" (the pre-fix shape).
        List<String> hits = memoryService.searchMemories(topic);

        assertThat(hits)
                .as("M4 fix: searchMemories must refuse to query when no orgId is resolvable "
                        + "from AgentContextHolder or SecurityContext. The pre-fix shape "
                        + "(returns every cross-tenant match) is what this assertion guards "
                        + "against — a non-empty result here means the defensive fallback "
                        + "regressed to 'match everything'.")
                .isEmpty();
    }
}
