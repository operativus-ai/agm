package com.operativus.agentmanager.integration.knowledge;

import com.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: §M9 — pins that {@code KnowledgeContentRepository.incrementAccessCount}
 * actually increments the {@code access_count} column and is idempotent across repeated calls.
 * The production call site is {@code KnowledgeService.search()} which invokes this after a
 * vector similarity search; this test verifies the @Modifying JPQL at the repo level.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class KnowledgeAccessCountRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private KnowledgeContentRepository knowledgeContentRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void incrementAccessCount_incrementsColumnByOne() {
        UUID id = seedContent("kac-doc-single.txt");

        assertThat(readAccessCount(id))
                .as("access_count must start at 0")
                .isEqualTo(0);

        knowledgeContentRepo.incrementAccessCount(List.of(id));

        assertThat(readAccessCount(id))
                .as("access_count must be 1 after one increment call")
                .isEqualTo(1);
    }

    @Test
    void incrementAccessCount_accumulatesAcrossRepeatedCalls() {
        UUID id = seedContent("kac-doc-multi.txt");

        knowledgeContentRepo.incrementAccessCount(List.of(id));
        knowledgeContentRepo.incrementAccessCount(List.of(id));
        knowledgeContentRepo.incrementAccessCount(List.of(id));

        assertThat(readAccessCount(id))
                .as("access_count must be 3 after three increment calls")
                .isEqualTo(3);
    }

    @Test
    void incrementAccessCount_scopedToRequestedIds_otherRowsUnchanged() {
        UUID targetId = seedContent("kac-target.txt");
        UUID bystander = seedContent("kac-bystander.txt");

        knowledgeContentRepo.incrementAccessCount(List.of(targetId));

        assertThat(readAccessCount(targetId))
                .as("targeted row must be incremented")
                .isEqualTo(1);
        assertThat(readAccessCount(bystander))
                .as("bystander row must not be incremented")
                .isEqualTo(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID seedContent(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_contents
                  (id, name, description, content_type, uri, content_hash,
                   size, status, metadata, vector_ids, access_count, created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, NOW(), NOW())
                """,
                id, name, "kac fixture", "text/plain", "fixture://" + name,
                "hash-" + id, 0, "COMPLETED", "{}", new UUID[0], 0);
        return id;
    }

    private int readAccessCount(UUID id) {
        return jdbc.queryForObject(
                "SELECT access_count FROM knowledge_contents WHERE id = ?::uuid",
                Integer.class,
                id.toString());
    }
}
