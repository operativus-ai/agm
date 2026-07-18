package ai.operativus.agentmanager.integration.memory;

import ai.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import ai.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import ai.operativus.agentmanager.control.service.MemoryService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModel;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain Responsibility: Pins the transactional consistency of
 *   {@link MemoryService#addMemory(String, String)} under embedding-API failure.
 *   {@code addMemory} is {@code @Transactional} and runs three writes in order:
 *   <ol>
 *     <li>{@code vectorStore.add(doc)} — embeds + inserts into {@code vector_store}</li>
 *     <li>{@code agenticMemoryRepository.save(memoryEntity)} — ledger row</li>
 *     <li>{@code outboxRepository.save(outbox)} — async-processing outbox</li>
 *   </ol>
 *
 *   <p>If the embedding API throws on step 1, the Spring {@code @Transactional} boundary
 *   MUST roll back so that no half-committed state remains — specifically, the ledger
 *   ({@code agentic_memories}) and outbox ({@code agentic_memory_outbox}) tables must
 *   NOT contain rows for the failed content. The vector_store write is part of the same
 *   transaction (pgvector via Spring's JDBC, sharing the connection), so it must also
 *   roll back.
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>Embed failure → no half-committed state</strong>: scripted exception
 *         on FakeEmbeddingModel; assert addMemory propagates RuntimeException AND all
 *         three tables remain unchanged for this content.</li>
 *     <li><strong>Recovery after failure</strong>: same content can be re-added in a
 *         subsequent call (the @Transactional rollback released any potential row-level
 *         contention).</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class MemoryEmbedFailureRuntimeTest extends BaseIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private AgenticMemoryRepository agenticMemoryRepository;
    @Autowired private AgenticMemoryOutboxRepository outboxRepository;
    @Autowired private FakeEmbeddingModel fakeEmbedding;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agentic_memory_outbox");
        jdbc.update("DELETE FROM agentic_memories");
        jdbc.update("DELETE FROM vector_store");
        fakeEmbedding.reset();
    }

    @Test
    void embedFailure_addMemoryRollsBackCleanly_noHalfCommittedState() {
        String userId = "m2-user-" + UUID.randomUUID();
        String content = "M2 embed-failure probe — " + UUID.randomUUID();

        // Script the next embedding call to throw. addMemory will hit this on
        // vectorStore.add(doc), and the Spring @Transactional boundary must roll back
        // the entire method body so no ledger or outbox row lands.
        fakeEmbedding.failNextCallWith(new RuntimeException("simulated embedding API outage"));

        long ledgerBefore = agenticMemoryRepository.count();
        long outboxBefore = outboxRepository.count();
        Long vectorBefore = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store", Long.class);

        ScopedValue.where(AgentContextHolder.orgId, "org-m2").run(() -> {
            assertThatThrownBy(() -> memoryService.addMemory(content, userId))
                    .as("embed failure must propagate as RuntimeException — the method "
                            + "is @Transactional and the catch-less body lets Spring see "
                            + "the exception and roll back. A swallowed exception here "
                            + "would mean the rollback didn't fire and the next assertions "
                            + "will detect the leaked rows")
                    .isInstanceOf(RuntimeException.class);
        });

        long ledgerAfter = agenticMemoryRepository.count();
        long outboxAfter = outboxRepository.count();
        Long vectorAfter = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store", Long.class);

        assertThat(ledgerAfter)
                .as("agentic_memories must be unchanged — a +1 here means the embed-API "
                        + "failure left a ledger row without a vector_store backing")
                .isEqualTo(ledgerBefore);
        assertThat(outboxAfter)
                .as("agentic_memory_outbox must be unchanged — a +1 here means the outbox "
                        + "row landed but the vector + ledger writes were rolled back")
                .isEqualTo(outboxBefore);
        assertThat(vectorAfter)
                .as("vector_store must be unchanged — a +1 here means pgvector committed "
                        + "outside the @Transactional boundary (separate connection?)")
                .isEqualTo(vectorBefore);
    }

    @Test
    void afterEmbedFailure_addMemoryRecovers_cleanRow() {
        String userId = "m2-recover-" + UUID.randomUUID();
        String content = "M2 recovery payload — " + UUID.randomUUID();

        // Attempt 1: scripted to fail
        fakeEmbedding.failNextCallWith(new RuntimeException("transient 429"));
        ScopedValue.where(AgentContextHolder.orgId, "org-m2-recover").run(() -> {
            assertThatThrownBy(() -> memoryService.addMemory(content, userId))
                    .isInstanceOf(RuntimeException.class);
        });

        // Attempt 2: should succeed (script auto-cleared after one shot)
        ScopedValue.where(AgentContextHolder.orgId, "org-m2-recover").run(() -> {
            memoryService.addMemory(content, userId);
        });

        // Exactly one row exists for this user after the recovery — the rollback released
        // any logical lock; the second call landed cleanly.
        Long ledgerCount = jdbc.queryForObject(
                "SELECT count(*) FROM agentic_memories WHERE user_id = ?",
                Long.class, userId);
        Long outboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM agentic_memory_outbox o "
                        + "JOIN agentic_memories m ON o.memory_id = m.memory_id "
                        + "WHERE m.user_id = ?",
                Long.class, userId);

        assertThat(ledgerCount)
                .as("recovery must produce exactly one ledger row — 0 = retry blocked, "
                        + "2 = the failed attempt persisted state despite the rollback")
                .isEqualTo(1L);
        assertThat(outboxCount)
                .as("recovery must produce exactly one outbox row — orphaned/double rows "
                        + "would indicate the transaction boundaries were inconsistent")
                .isEqualTo(1L);
    }
}
