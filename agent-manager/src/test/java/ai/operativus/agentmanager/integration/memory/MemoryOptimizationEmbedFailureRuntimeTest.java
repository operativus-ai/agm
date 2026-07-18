package ai.operativus.agentmanager.integration.memory;

import ai.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import ai.operativus.agentmanager.control.service.MemoryService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModel;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins {@link MemoryService#optimizeMemories(String)} against
 *   permanent data loss when the consolidated-replacement {@code addMemory} call fails
 *   on the embedding API.
 *
 *   <p><strong>M6 production fix shipped in this PR.</strong> Pre-fix order inside the
 *   batch try-block:
 *   <ol>
 *     <li>{@code vectorStore.delete(oldVectorIds)} — commits</li>
 *     <li>{@code agenticMemoryRepository.deleteAll(batch)} — commits</li>
 *     <li>{@code addMemory(summary, userId, topics)} — @Transactional; if embed throws,
 *         this method rolls back but steps 1 + 2 are already committed</li>
 *   </ol>
 *   The catch at the end of the batch logged a warning and continued, swallowing the
 *   embed exception. <strong>Result: the user's old memories were permanently deleted
 *   with no replacement.</strong>
 *
 *   <p>Fix in this PR: reorder to <em>add-first, delete-after</em>. If {@code addMemory}
 *   throws, the original batch rows remain intact and the next optimization sweep can
 *   retry. The brief duplication window (new + old both present until the delete
 *   commits) is acceptable; later consolidation passes deduplicate.
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>Embed failure preserves original memories</strong>: M6 regression-lock.
 *         Seed 3 memories, script embed to fail on the consolidated-replacement
 *         {@code addMemory} call, run optimizeMemories. The original 3 rows must
 *         remain in {@code agentic_memories} AND {@code vector_store}.</li>
 *     <li><strong>Successful optimization deletes originals and adds consolidation</strong>:
 *         happy-path regression-lock. Without this, M6's fix could silently regress
 *         to "always preserve everything" by simply never running the delete.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class MemoryOptimizationEmbedFailureRuntimeTest extends BaseIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private AgenticMemoryRepository memoryRepo;
    @Autowired private FakeEmbeddingModel fakeEmbedding;
    @Autowired private FakeChatModel fakeChat;

    private static final String ORG_ID = "org-m6";

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agentic_memory_outbox");
        jdbc.update("DELETE FROM agentic_memories");
        jdbc.update("DELETE FROM vector_store");
        fakeEmbedding.reset();
        fakeChat.reset();
    }

    @Test
    void optimizeMemories_embedFailureOnConsolidatedReplacement_preservesOriginals_M6ProductionFix() {
        String userId = "user-m6-failure-" + UUID.randomUUID();

        // Seed 3 memories for this user under a bound orgId. Batch size is 20 in the
        // service, but the implementation only consolidates batches with size >= 3, so
        // exactly 3 is the minimum batch that triggers the optimization path.
        ScopedValue.where(AgentContextHolder.orgId, ORG_ID).run(() -> {
            memoryService.addMemory("M6 fact alpha — " + UUID.randomUUID(), userId);
            memoryService.addMemory("M6 fact bravo — " + UUID.randomUUID(), userId);
            memoryService.addMemory("M6 fact charlie — " + UUID.randomUUID(), userId);
        });

        long ledgerSeeded = memoryRepo.findByUserId(userId).size();
        Long vectorSeeded = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'userId' = ?",
                Long.class, userId);
        assertThat(ledgerSeeded).as("precondition: 3 ledger rows seeded").isEqualTo(3L);
        assertThat(vectorSeeded).as("precondition: 3 vector rows seeded").isEqualTo(3L);

        // Optimize calls the chat client twice per batch (summary + topics) before the
        // consolidated-replacement addMemory triggers the embedding API. Script the
        // chat client to return non-blank strings so optimize proceeds past the
        // "summary == null || isBlank → continue" early-exit, then fail the next embed
        // call so the consolidated-replacement addMemory throws.
        fakeChat.respondWith("- consolidated summary " + UUID.randomUUID());
        fakeChat.respondWith("topic-a, topic-b, topic-c");
        fakeEmbedding.failNextCallWith(new RuntimeException("M6 simulated embed-API outage"));

        ScopedValue.where(AgentContextHolder.orgId, ORG_ID).run(() -> {
            // optimizeMemories swallows per-batch exceptions in its catch block and
            // continues — it does NOT propagate. The contract this test pins is that
            // the swallow does NOT come at the cost of data loss.
            memoryService.optimizeMemories(userId);
        });

        // M6 regression-lock: the original 3 memories MUST still exist. A 0 here is
        // the catastrophic pre-fix data-loss shape (delete already committed, embed
        // failed on the replacement).
        long ledgerAfter = memoryRepo.findByUserId(userId).size();
        Long vectorAfter = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'userId' = ?",
                Long.class, userId);

        assertThat(ledgerAfter)
                .as("M6 fix: optimizeMemories must preserve the original ledger rows when "
                        + "the consolidated-replacement addMemory throws. A count less than "
                        + "3 here is the pre-fix data-loss shape — old memories were deleted "
                        + "before the replacement was confirmed to embed successfully.")
                .isEqualTo(3L);
        assertThat(vectorAfter)
                .as("M6 fix: vector_store must also preserve the original rows when embed "
                        + "fails on the replacement. A count less than 3 means vectorStore.delete "
                        + "fired before the addMemory verification.")
                .isEqualTo(3L);
    }

    @Test
    void optimizeMemories_successPath_deletesOriginalsAndAddsConsolidation_M6HappyPathLock() {
        String userId = "user-m6-success-" + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_ID).run(() -> {
            memoryService.addMemory("M6 success alpha — " + UUID.randomUUID(), userId);
            memoryService.addMemory("M6 success bravo — " + UUID.randomUUID(), userId);
            memoryService.addMemory("M6 success charlie — " + UUID.randomUUID(), userId);
        });

        long ledgerSeeded = memoryRepo.findByUserId(userId).size();
        assertThat(ledgerSeeded).as("precondition: 3 ledger rows seeded").isEqualTo(3L);

        // No embed failure scripted — optimization should land the happy path.
        String consolidated = "- M6 happy-path consolidated " + UUID.randomUUID();
        fakeChat.respondWith(consolidated);
        fakeChat.respondWith("topic-x, topic-y, topic-z");

        ScopedValue.where(AgentContextHolder.orgId, ORG_ID).run(() -> {
            memoryService.optimizeMemories(userId);
        });

        List<AgenticMemoryEntity> after = memoryRepo.findByUserId(userId);
        assertThat(after)
                .as("M6 happy-path lock: after a successful optimization, the ledger must "
                        + "carry EXACTLY ONE row for this user — the consolidated replacement. "
                        + "A count of 3 (originals untouched) means the delete-after-add step "
                        + "silently regressed to a no-op; a count > 1 means the deletion "
                        + "missed some originals.")
                .hasSize(1);
        assertThat(after.get(0).getMemory())
                .as("the surviving ledger row must be the consolidated summary, not one of "
                        + "the original 3 — a mismatch means the deletion targeted the wrong "
                        + "row set")
                .isEqualTo(consolidated);
    }
}
