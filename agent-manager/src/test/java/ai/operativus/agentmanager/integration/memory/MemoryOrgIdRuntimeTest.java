package ai.operativus.agentmanager.integration.memory;

import ai.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import ai.operativus.agentmanager.control.service.MemoryService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
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
 * Domain Responsibility: Pins the M7 relational ledger orgId contract —
 *   that {@link MemoryService#addMemory} mirrors orgId into the
 *   {@code agentic_memories} row, that {@link MemoryService#deleteMemories}
 *   cleans the relational row efficiently without a full-table scan, and
 *   that {@link MemoryService#deleteAllMemoriesForOrg} wipes one org's
 *   memories while leaving other orgs intact.
 *
 *   <p><strong>M7 production fixes shipped in this PR:</strong>
 *   <ul>
 *     <li>{@code addMemory} now calls {@code memoryEntity.setOrgId(orgId)} so
 *         the relational ledger mirrors the orgId already written to the vector
 *         store Document metadata in M4.</li>
 *     <li>{@code deleteMemories} now uses {@code findByVectorIdIn(ids)} instead
 *         of {@code findAll().stream().filter()} — eliminates the O(N) full-table
 *         scan that grew with every memory added to the system.</li>
 *     <li>{@code deleteAllMemoriesForOrg} is a new method that org-offboarding
 *         and compliance workflows can call to atomically wipe all of an org's
 *         memories from the vector store, outbox, and relational ledger.</li>
 *   </ul>
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>addMemory persists orgId to relational ledger</strong>: the
 *         {@code agentic_memories} row must carry orgId matching the vector_store
 *         Document metadata.</li>
 *     <li><strong>deleteMemories cleans relational row by vectorId</strong>: after
 *         {@code deleteMemories(vectorId)}, the {@code agentic_memories} row must
 *         be gone — no orphan left by the old full-scan bug.</li>
 *     <li><strong>deleteAllMemoriesForOrg wipes org while preserving others</strong>:
 *         org A's memories disappear, org B's memories survive.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class MemoryOrgIdRuntimeTest extends BaseIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private AgenticMemoryRepository agenticMemoryRepository;

    private static final String ORG_A = "org-m7-alpha";
    private static final String ORG_B = "org-m7-bravo";

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agentic_memory_outbox");
        jdbc.update("DELETE FROM agentic_memories");
        jdbc.update("DELETE FROM vector_store");
    }

    @Test
    void addMemory_persistsOrgIdToRelationalLedger_M7ProductionFix() {
        String content = "M7 ledger orgId probe " + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory(content, "ledger-probe-user"));

        List<AgenticMemoryEntity> rows = agenticMemoryRepository.findByOrgId(ORG_A);

        assertThat(rows)
                .as("M7 fix: addMemory must mirror the orgId resolved from AgentContextHolder "
                        + "into the agentic_memories row. An empty result here means setOrgId "
                        + "was not called — org-scoped delete and retention queries have nothing "
                        + "to filter on.")
                .isNotEmpty();
        assertThat(rows)
                .allMatch(r -> ORG_A.equals(r.getOrgId()),
                        "every row written under org A must carry orgId = '" + ORG_A + "'");
        assertThat(rows)
                .anyMatch(r -> content.equals(r.getMemory()),
                        "the seeded memory content must appear in the relational row");
    }

    @Test
    void deleteMemories_removesRelationalRowByVectorId_M7ProductionFix() {
        String content = "M7 delete-by-vectorId probe " + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory(content, "delete-probe-user"));

        List<AgenticMemoryEntity> seeded = agenticMemoryRepository.findByOrgId(ORG_A);
        assertThat(seeded).as("pre-condition: memory must exist before delete").isNotEmpty();

        String vectorId = seeded.get(0).getVectorId();
        assertThat(vectorId).as("pre-condition: vectorId must be populated").isNotBlank();

        // deleteMemories is the path exercised by MemoryController DELETE /api/memories
        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.deleteMemories(List.of(vectorId)));

        List<AgenticMemoryEntity> after = agenticMemoryRepository.findByOrgId(ORG_A);
        assertThat(after)
                .as("M7 fix: deleteMemories must remove the agentic_memories row that owns "
                        + "the deleted vectorId. An orphan here means the old findAll().stream() "
                        + "filter regressed or findByVectorIdIn is not matching correctly.")
                .isEmpty();

        Long vectorRows = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE id = ?::uuid", Long.class, vectorId);
        assertThat(vectorRows)
                .as("deleteMemories must also remove the vector_store document")
                .isZero();
    }

    @Test
    void deleteAllMemoriesForOrg_wipesOrgAWhilePreservingOrgB_M7ProductionFix() {
        String orgAContent = "M7 org-cascade A " + UUID.randomUUID();
        String orgBContent = "M7 org-cascade B " + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, ORG_A)
                .run(() -> memoryService.addMemory(orgAContent, "user-a"));
        ScopedValue.where(AgentContextHolder.orgId, ORG_B)
                .run(() -> memoryService.addMemory(orgBContent, "user-b"));

        assertThat(agenticMemoryRepository.findByOrgId(ORG_A))
                .as("pre-condition: org A row must exist").isNotEmpty();
        assertThat(agenticMemoryRepository.findByOrgId(ORG_B))
                .as("pre-condition: org B row must exist").isNotEmpty();

        memoryService.deleteAllMemoriesForOrg(ORG_A);

        assertThat(agenticMemoryRepository.findByOrgId(ORG_A))
                .as("M7 fix: deleteAllMemoriesForOrg must wipe every agentic_memories row "
                        + "for the target org. A non-empty result means the relational delete "
                        + "did not run or findByOrgId returned the wrong set.")
                .isEmpty();

        List<AgenticMemoryEntity> orgBRows = agenticMemoryRepository.findByOrgId(ORG_B);
        assertThat(orgBRows)
                .as("deleteAllMemoriesForOrg must NOT touch other orgs' memories — "
                        + "org B's row must survive the org A cascade.")
                .isNotEmpty();
        assertThat(orgBRows)
                .anyMatch(r -> orgBContent.equals(r.getMemory()),
                        "org B's seeded memory content must be intact after org A was deleted");

        Long orgAVectorRows = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                Long.class, ORG_A);
        assertThat(orgAVectorRows)
                .as("deleteAllMemoriesForOrg must also remove org A's vector_store documents")
                .isZero();
    }
}
