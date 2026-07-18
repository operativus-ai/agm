package com.operativus.agentmanager.integration.memory;

import com.operativus.agentmanager.compute.memory.MemoryConsolidationWorker;
import com.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import com.operativus.agentmanager.control.service.MemoryService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the tenant-isolation contract for
 *   {@link MemoryConsolidationWorker#processPendingMemoryExtractions()}.
 *
 *   <p><strong>M5 production fix shipped in this PR.</strong> Pre-fix the worker:
 *   <ul>
 *     <li>Called {@code vectorStore.similaritySearch(rawMemory)} with NO filter — when
 *         org A's outbox event was processed, the similarity search could return
 *         org B's semantically-identical memory as a {@code score > 0.85} collision.</li>
 *     <li>Then asked the LLM to merge: prompt body was
 *         {@code "Existing: <org B's text>. New: <org A's text>"} — cross-tenant content
 *         leak into the LLM provider's request/response surface AND into the synthesized
 *         memory text.</li>
 *     <li>Wrote the synthesized Document with metadata containing {@code outboxId},
 *         {@code memoryId}, {@code userId}, {@code memoryTier}, {@code agentId},
 *         {@code teamId} — but NOT {@code orgId}. Post-M4 the consolidated row was
 *         invisible to every {@code MemoryService.searchMemories} call because the M4
 *         filter has nothing to match against.</li>
 *   </ul>
 *
 *   <p>Fix in this PR:
 *   <ol>
 *     <li>Worker reads the parent memory's {@code orgId} from the original
 *         {@code vector_store} row metadata (written by {@code MemoryService.addMemory}
 *         under the M4 fix), located via {@code AgenticMemoryEntity.vectorId}.</li>
 *     <li>The similarity search is now AND-filtered by {@code orgId == <parentOrgId>} so
 *         only same-org rows are candidates for the LLM merge.</li>
 *     <li>The synthesized Document carries {@code orgId} metadata so the M4
 *         {@code searchMemories} filter has something to match against post-consolidation.</li>
 *     <li>Defensive: when the parent's orgId can't be resolved (pre-M4 rows, system-
 *         background paths), the worker SKIPS the LLM consolidation entirely and writes
 *         the raw memory as-is. This avoids cross-tenant leakage when context is missing
 *         rather than falling through to "match everything".</li>
 *   </ol>
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>Same-org consolidation still fires</strong>: regression-lock for the
 *         happy path — when the parent has a resolvable orgId AND a same-org collision
 *         exists at score > 0.85, the LLM merge still runs and the synthesized doc
 *         carries the parent's orgId.</li>
 *     <li><strong>Cross-tenant LLM merge prevented</strong>: org A's outbox event with
 *         org B's memory in vector_store at identical text must NOT cause the LLM merge
 *         to run with org B's content as "Existing". FakeChatModel.receivedPrompts()
 *         carries the exact prompt text — the assertion verifies no org-B content leaked.</li>
 *     <li><strong>Post-consolidation row visible to same-org search, hidden cross-org</strong>:
 *         end-to-end pin. The consolidated row must carry orgId metadata such that the
 *         M4 search filter surfaces it for the parent's org but NOT for any other org.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class MemoryConsolidationTenantIsolationRuntimeTest extends BaseIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private MemoryConsolidationWorker consolidationWorker;
    @Autowired private AgenticMemoryOutboxRepository outboxRepo;
    @Autowired private FakeChatModel fakeChat;

    private static final String ORG_A = "org-m5-alpha";
    private static final String ORG_B = "org-m5-bravo";

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agentic_memory_outbox");
        jdbc.update("DELETE FROM agentic_memories");
        jdbc.update("DELETE FROM vector_store");
        fakeChat.reset();
    }

    @Test
    void sameOrgConsolidation_stillFiresAndTagsSynthesisWithOrgId_M5RegressionLock() {
        // Two memories in org A: the second one is a near-duplicate of the first, so
        // FakeEmbeddingModel's identical-text → identical-vector → score 1.0 guarantee
        // surfaces the first as a > 0.85 collision when the worker processes the second.
        String text = "M5 alpha — user prefers React over Vue " + UUID.randomUUID();
        String synthesized = "Consolidated: user is a React developer";
        fakeChat.respondWith(synthesized);

        ScopedValue.where(AgentContextHolder.orgId, ORG_A).run(() -> {
            memoryService.addMemory(text, "alice");
            memoryService.addMemory(text, "alice"); // second outbox event will see first as collision
        });

        consolidationWorker.processPendingMemoryExtractions();

        assertThat(fakeChat.receivedPrompts())
                .as("M5 regression-lock: same-org collision at score > 0.85 must still fire "
                        + "the LLM merge. A zero count here means the worker incorrectly "
                        + "skipped consolidation despite a resolvable parent orgId — the "
                        + "fix's orgId-filter regressed to over-restrictive.")
                .isNotEmpty();

        // The synthesized row landed in vector_store with orgId=A metadata.
        Long synthRows = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store "
                        + "WHERE content = ? AND metadata->>'orgId' = ?",
                Long.class, synthesized, ORG_A);
        assertEquals(1L, synthRows,
                "M5 fix: the synthesized consolidated row must carry orgId metadata. A zero "
                        + "count here means processOne wrote the Document without orgId — the "
                        + "row is invisible to the M4 search filter going forward.");
    }

    @Test
    void crossTenantCollision_doesNotLeakOrgBContentIntoLlmPrompt_M5ProductionFix() {
        // Two orgs hold memories that share the colliding stem but each marks its own
        // payload with a distinctive tenant marker. FakeEmbeddingModel hashes the full
        // text, so identical stems produce identical vectors — pre-M5, org A's
        // unfiltered similaritySearch would surface org B's row as a > 0.85 collision
        // and the LLM merge prompt would contain BOTH the "PRIVATE TO ORG A" and the
        // "PRIVATE TO ORG B" markers (cross-tenant LLM-side content leak).
        //
        // The cross-tenant-leak signature is: a single prompt that mixes both markers.
        // Each org's own self-consolidation legitimately contains its own marker only,
        // and that is the expected post-M5 shape.
        String collidingText = "M5 cross-tenant collision body " + UUID.randomUUID();
        String orgBText = collidingText + " — PRIVATE TO ORG B " + UUID.randomUUID();
        String orgAText = collidingText + " — PRIVATE TO ORG A " + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_B).run(() -> {
            memoryService.addMemory(orgBText, "shared-userid");
        });
        ScopedValue.where(AgentContextHolder.orgId, ORG_A).run(() -> {
            memoryService.addMemory(orgAText, "shared-userid");
        });

        consolidationWorker.processPendingMemoryExtractions();

        assertThat(fakeChat.receivedPrompts())
                .as("M5 fix: every LLM consolidation prompt must contain at most one tenant's "
                        + "marker. A prompt containing BOTH 'PRIVATE TO ORG A' and 'PRIVATE TO "
                        + "ORG B' is the cross-tenant-leak signature — the unfiltered "
                        + "similaritySearch surfaced the other tenant's row as 'Existing "
                        + "context' and the LLM provider saw both tenants' content.")
                .noneSatisfy(prompt -> {
                    String text = prompt.getContents();
                    assertThat(text).contains("PRIVATE TO ORG A");
                    assertThat(text).contains("PRIVATE TO ORG B");
                });
    }

    @Test
    void postConsolidation_synthRowVisibleToSameOrgSearch_hiddenFromOtherOrg_M5EndToEnd() {
        // Trigger a same-org consolidation so a synthesis row lands in vector_store.
        String orgAText = "M5 e2e alpha body " + UUID.randomUUID();
        String synthesized = "Consolidated e2e: user M5 marker " + UUID.randomUUID();
        fakeChat.respondWith(synthesized);

        ScopedValue.where(AgentContextHolder.orgId, ORG_A).run(() -> {
            memoryService.addMemory(orgAText, "alice");
            memoryService.addMemory(orgAText, "alice");
        });

        consolidationWorker.processPendingMemoryExtractions();

        // M4 search from org A must find the consolidated synthesis (i.e. the row
        // carries orgId=A metadata). Pre-M5 the synthesis had no orgId and was invisible
        // to every search post-M4.
        List<String> orgAHits = ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .call(() -> memoryService.searchMemories(synthesized));

        assertThat(orgAHits)
                .as("M5 fix: the consolidated synthesis row must be searchable from the same "
                        + "org via the M4-filtered search. An empty result means the synthesis "
                        + "Document was written without orgId metadata — it's invisible "
                        + "post-consolidation and the consolidation feature is non-functional "
                        + "for tenant-scoped queries.")
                .anyMatch(t -> t.equals(synthesized) || t.contains("M5 marker"));

        // Org B searching for the same text must NOT see the synthesis (cross-org isolation).
        List<String> orgBHits = ScopedValue.where(AgentContextHolder.orgId, ORG_B)
                .call(() -> memoryService.searchMemories(synthesized));

        assertThat(orgBHits)
                .as("M5 fix: the consolidated row must NOT leak to org B's tenant-scoped "
                        + "search. A non-empty result here means the synthesis was tagged "
                        + "with the wrong orgId, or the M4 search filter regressed.")
                .doesNotContain(synthesized)
                .noneMatch(t -> t.contains("M5 marker"));

        // Outbox event for the second addMemory transitioned PENDING → COMPLETED.
        List<AgenticMemoryOutboxEntity> completed = outboxRepo.findAll().stream()
                .filter(o -> o.getStatus() == AgenticMemoryOutboxEntity.OutboxStatus.COMPLETED)
                .toList();
        assertThat(completed)
                .as("at least one outbox event must transition to COMPLETED during the "
                        + "consolidation pass — a zero count means processOne threw or "
                        + "the worker never picked up the events")
                .isNotEmpty();
    }
}
