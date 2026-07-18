package ai.operativus.agentmanager.integration.memory;

import ai.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import ai.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity;
import ai.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity.OutboxStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the destructive {@link
 *   ai.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository#deleteByMemoryIdIn}
 *   query against silent data-loss regressions. The query has 5 production call sites
 *   (3 in {@code MemoryService} — single-delete, bulk-delete, replaceWithNewer; 2 in
 *   {@code MemoryErasureHandler.erase}), and a wrong WHERE clause would silently delete
 *   outbox rows for other memories without any caller noticing — outbox events would
 *   simply stop firing for affected memories with no error surface.
 *
 *   <p>Without these tests, a JPQL refactor (e.g. adding a JOIN that accidentally
 *   broadens the predicate, or changing the parameter binding) could ship to prod and
 *   only surface as "downstream consumers stopped receiving events for memory X" — a
 *   class of bug that's invisible to every existing service-level test that just asserts
 *   the count returned by the delete.
 *
 *   <p>Pins:
 *   <ul>
 *     <li>HAPPY: deletes ALL rows for the specified memoryIds (multiple per memory)</li>
 *     <li>SCOPE: rows for other memoryIds are UNTOUCHED — no over-deletion</li>
 *     <li>RETURN: the int return value equals the actual number of rows deleted</li>
 *     <li>IDEMPOTENT: re-running with the same set returns 0 (the rows are already gone)</li>
 *     <li>SINGLE: deleting one memoryId of three leaves the other two intact</li>
 *     <li>STATUS-AGNOSTIC: deletion ignores OutboxStatus (PENDING/PROCESSING/COMPLETED/FAILED all deleted equally)</li>
 *   </ul>
 *
 *   <p>NOT pinned here: empty-collection input. JPQL {@code IN ()} is dialect-dependent
 *   and most Hibernate dialects throw; production callers (MemoryService, MemoryErasureHandler)
 *   short-circuit at the caller before invoking this method. Adding an empty-set test would
 *   pin Hibernate behavior we don't control.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgenticMemoryOutboxRepositoryDeleteRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private AgenticMemoryOutboxRepository outboxRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    @Transactional
    void deleteByMemoryIdIn_deletesAllRowsForTargetedMemoryAndReturnsCorrectCount() {
        UUID memoryA = seedMemory();
        outboxRepo.save(row(memoryA, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryA, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryA, OutboxStatus.COMPLETED));
        // sanity: rows are present before the delete
        assertEquals(3, outboxRepo.count(), "fixture must seed 3 rows before the delete");

        int deleted = outboxRepo.deleteByMemoryIdIn(Set.of(memoryA));

        assertEquals(3, deleted, "must report the actual number of rows deleted");
        assertEquals(0, outboxRepo.count(), "all 3 rows for memoryA must be gone");
    }

    @Test
    @Transactional
    void deleteByMemoryIdIn_rowsForOtherMemoriesAreUntouched() {
        // The CRITICAL property: deleting memoryA must NOT affect memoryB rows.
        // A buggy WHERE clause (e.g. missing the IN binding, or using OR instead of IN)
        // would silently over-delete.
        UUID memoryA = seedMemory();
        UUID memoryB = seedMemory();
        UUID memoryC = seedMemory();

        outboxRepo.save(row(memoryA, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryA, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryB, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryB, OutboxStatus.PROCESSING));
        outboxRepo.save(row(memoryB, OutboxStatus.COMPLETED));
        outboxRepo.save(row(memoryC, OutboxStatus.PENDING));

        int deleted = outboxRepo.deleteByMemoryIdIn(Set.of(memoryA));

        assertEquals(2, deleted, "exactly 2 rows for memoryA must be deleted");

        List<AgenticMemoryOutboxEntity> remaining = outboxRepo.findAll();
        assertEquals(4, remaining.size(),
                "memoryB (3 rows) + memoryC (1 row) = 4 must survive; got: " + remaining.size());

        long memoryBSurvivors = remaining.stream().filter(r -> memoryB.equals(r.getMemoryId())).count();
        long memoryCSurvivors = remaining.stream().filter(r -> memoryC.equals(r.getMemoryId())).count();
        long memoryASurvivors = remaining.stream().filter(r -> memoryA.equals(r.getMemoryId())).count();
        assertEquals(3, memoryBSurvivors, "all 3 memoryB rows must remain — over-deletion bug");
        assertEquals(1, memoryCSurvivors, "the memoryC row must remain — over-deletion bug");
        assertEquals(0, memoryASurvivors, "no memoryA rows must remain — under-deletion bug");
    }

    @Test
    @Transactional
    void deleteByMemoryIdIn_multipleMemoryIdsInOneCall_deletesAllListedAndOnlyListed() {
        // The IN-clause case: pass {A, C}, expect A and C deleted, B untouched.
        UUID memoryA = seedMemory();
        UUID memoryB = seedMemory();
        UUID memoryC = seedMemory();

        outboxRepo.save(row(memoryA, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryA, OutboxStatus.PROCESSING));
        outboxRepo.save(row(memoryB, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryB, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryC, OutboxStatus.COMPLETED));

        int deleted = outboxRepo.deleteByMemoryIdIn(Set.of(memoryA, memoryC));

        assertEquals(3, deleted, "memoryA (2 rows) + memoryC (1 row) = 3 must be deleted");

        List<AgenticMemoryOutboxEntity> remaining = outboxRepo.findAll();
        assertEquals(2, remaining.size(), "only memoryB's 2 rows must remain");
        assertTrue(remaining.stream().allMatch(r -> memoryB.equals(r.getMemoryId())),
                "all surviving rows must be for memoryB");
    }

    @Test
    @Transactional
    void deleteByMemoryIdIn_isIdempotent_secondCallReturnsZero() {
        UUID memoryA = seedMemory();
        outboxRepo.save(row(memoryA, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryA, OutboxStatus.COMPLETED));

        int firstCallDeleted = outboxRepo.deleteByMemoryIdIn(Set.of(memoryA));
        assertEquals(2, firstCallDeleted);

        // Second call: rows are already gone, must return 0 not throw.
        int secondCallDeleted = outboxRepo.deleteByMemoryIdIn(Set.of(memoryA));
        assertEquals(0, secondCallDeleted,
                "idempotent re-delete must return 0, not throw or report a stale count");
    }

    @Test
    @Transactional
    void deleteByMemoryIdIn_deletesAcrossAllOutboxStatuses() {
        // Defensive pin: the query must NOT silently restrict by status (no implicit
        // status='PENDING' filter). MemoryService.delete + MemoryErasureHandler call this
        // expecting ALL outbox state to be wiped for the memory, including COMPLETED rows
        // that haven't been auto-purged yet.
        UUID memoryA = seedMemory();
        for (OutboxStatus s : OutboxStatus.values()) {
            outboxRepo.save(row(memoryA, s));
        }
        assertEquals(4, outboxRepo.count(), "fixture must seed one row per status (4 statuses)");

        int deleted = outboxRepo.deleteByMemoryIdIn(Set.of(memoryA));

        assertEquals(4, deleted, "all 4 statuses must be deleted in one shot");
        assertEquals(0, outboxRepo.count(), "no rows must remain after status-agnostic delete");
    }

    @Test
    @Transactional
    void deleteByMemoryIdIn_unknownMemoryId_returnsZeroAndDoesNotTouchExistingRows() {
        UUID memoryA = seedMemory();
        // Unknown memory: NOT seeded into agentic_memories. The IN-filter doesn't care
        // whether the parent exists — it only matches against agentic_memory_outbox rows.
        UUID unknownMemory = UUID.randomUUID();
        outboxRepo.save(row(memoryA, OutboxStatus.PENDING));
        outboxRepo.save(row(memoryA, OutboxStatus.COMPLETED));

        int deleted = outboxRepo.deleteByMemoryIdIn(Set.of(unknownMemory));

        assertEquals(0, deleted, "non-matching memoryId must report 0 deleted");
        assertEquals(2, outboxRepo.count(),
                "existing rows must be untouched when the delete targets a non-matching memoryId");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * Seeds a minimal {@code agentic_memories} row so outbox FK
     * ({@code fk_agentic_memory_outbox_memory}) is satisfied. Returns the new memoryId
     * so each test can capture distinct parents.
     */
    private UUID seedMemory() {
        UUID memoryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO agentic_memories (memory_id, memory, user_id, memory_tier) VALUES (?, ?, ?, ?)",
                memoryId, "test-memory-content", "test-user", "USER_MEMORY");
        return memoryId;
    }

    private static AgenticMemoryOutboxEntity row(UUID memoryId, OutboxStatus status) {
        AgenticMemoryOutboxEntity row = new AgenticMemoryOutboxEntity();
        row.setOutboxId(UUID.randomUUID());
        row.setMemoryId(memoryId);
        row.setPayload("{\"event\":\"test\"}");
        row.setStatus(status);
        row.setRetryCount(0);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
