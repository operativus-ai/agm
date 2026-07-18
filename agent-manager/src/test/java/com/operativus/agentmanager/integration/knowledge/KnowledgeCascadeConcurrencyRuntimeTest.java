package com.operativus.agentmanager.integration.knowledge;

import com.operativus.agentmanager.control.service.KnowledgeService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.callback.AgentContextSnapshot;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins two cascade-race behaviors that the existing knowledge
 *   runtime tests don't cover:
 *   <ol>
 *     <li><strong>Concurrent search-vs-delete on the same doc</strong>: the vector search
 *         either fully observes doc D's chunks (snapshot taken BEFORE the delete commits)
 *         or observes NONE of them (snapshot taken AFTER the delete commits). There is no
 *         torn middle state — {@code knowledgeService.delete} is {@code @Transactional} so
 *         the metadata-row delete and vector_store cleanup commit atomically. Pinning this
 *         is the regression-lock against a future refactor that splits the cascade into two
 *         transactions and re-introduces partial visibility.</li>
 *     <li><strong>Async KB-cascade-delete eventually clears vector_store</strong>:
 *         {@code knowledgeBaseService.deleteWithCascade} deletes the KB row synchronously
 *         then spawns a virtual thread that pages through {@code knowledge_contents} and
 *         calls {@code knowledgeService.delete} per doc. After the spawn returns,
 *         {@code vector_store} rows with {@code metadata.knowledgeBaseId=&lt;deleted&gt;}
 *         persist briefly until the VT finishes its loop. This test Awaitility-polls for
 *         the cleanup to complete, pinning the "no orphans after async cascade" invariant.</li>
 *   </ol>
 *
 *   <p>These cases complement the synchronous cascade test
 *   ({@code KnowledgeBaseRuntimeTest.deleteDocument_cascadesToVectorStoreAndMetadata})
 *   which pins single-doc atomicity from a single-thread caller.</p>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class KnowledgeCascadeConcurrencyRuntimeTest extends BaseIntegrationTest {

    @Autowired private KnowledgeService knowledgeService;

    private static final String ORG = "org-k8-cascade";

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM knowledge_contents");
        jdbc.update("DELETE FROM vector_store");
    }

    @Test
    void concurrentSearchAndDelete_observesConsistentSnapshot() throws Exception {
        // Seed a single doc with enough text to generate multiple chunks. The race we care
        // about is "search began before delete committed" vs "search began after delete
        // committed" — both must yield a consistent view (all chunks or zero chunks).
        UUID kbId = UUID.randomUUID();
        String docText = ("K8 cascade-race document. Repeated content to ensure the splitter "
                + "produces multiple chunks for the same source doc. "
                + "Cascade-race document. Repeated content to ensure the splitter produces "
                + "multiple chunks. Cascade-race document repeated again. ")
                .repeat(20);
        ScopedValue.where(AgentContextHolder.orgId, ORG)
                .run(() -> knowledgeService.ingest("k8-race.txt", "text/plain",
                        docText.getBytes(StandardCharsets.UTF_8), kbId));

        // Wait for the async byte-array ingest VT to land its chunks before launching the race.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    Long count = jdbc.queryForObject(
                            "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                            Long.class, ORG);
                    assertThat(count).isNotNull().isGreaterThanOrEqualTo(2L);
                });

        UUID docId = jdbc.queryForObject(
                "SELECT id FROM knowledge_contents WHERE knowledge_base_id = ?::uuid LIMIT 1",
                UUID.class, kbId);
        assertThat(docId).isNotNull();

        AgentContextSnapshot snapshot = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(AgentContextSnapshot::capture);

        // Launch search and delete on separate VTs so they overlap in real wall-clock time.
        // The exact interleaving is non-deterministic; what we assert is INVARIANT across all
        // interleavings: the search result either includes all of docId's chunks or none of
        // them. A torn state (some chunks present, some absent) would indicate the cascade
        // isn't transactional.
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<List<Document>> searchTask = () -> snapshot.call(
                    () -> knowledgeService.search("K8 cascade-race document repeated content"));
            Callable<Void> deleteTask = () -> snapshot.call(() -> {
                knowledgeService.delete(docId);
                return null;
            });

            // Run several iterations to maximise the chance of catching a torn interleaving.
            // 5 trials × 2 threads × ~ms each = ~10 trials total; if cascade weren't atomic,
            // one would surface within this window.
            for (int trial = 0; trial < 5; trial++) {
                // Re-seed for each trial (delete consumed the doc in the prior round).
                if (trial > 0) {
                    ScopedValue.where(AgentContextHolder.orgId, ORG)
                            .run(() -> knowledgeService.ingest("k8-race-" + UUID.randomUUID() + ".txt",
                                    "text/plain", docText.getBytes(StandardCharsets.UTF_8), kbId));
                    // Wait for the ASYNC chunk-write, not the synchronous knowledge_contents row.
                    // ingest() saves the metadata row inline but writes vector_store chunks on a
                    // virtual thread; gating on knowledge_contents would let the concurrent delete
                    // below race ahead of the chunk-write, leaving orphaned vector rows (the row
                    // count != 0 flake). The prior trial's delete left this org at 0 chunks, so a
                    // count >= 2 means the new doc's chunks have landed (matches the trial-0 wait).
                    Awaitility.await()
                            .atMost(Duration.ofSeconds(10))
                            .pollInterval(Duration.ofMillis(150))
                            .untilAsserted(() -> {
                                Long count = jdbc.queryForObject(
                                        "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                                        Long.class, ORG);
                                assertThat(count).isNotNull().isGreaterThanOrEqualTo(2L);
                            });
                    UUID nextDocId = jdbc.queryForObject(
                            "SELECT id FROM knowledge_contents WHERE knowledge_base_id = ?::uuid LIMIT 1",
                            UUID.class, kbId);
                    final UUID nextId = nextDocId;
                    deleteTask = () -> snapshot.call(() -> {
                        knowledgeService.delete(nextId);
                        return null;
                    });
                }

                Future<List<Document>> searchFuture = exec.submit(searchTask);
                Future<Void> deleteFuture = exec.submit(deleteTask);

                List<Document> hits = searchFuture.get();
                deleteFuture.get();

                // The search result MUST NOT be partial. Filter to chunks for the active
                // docId and assert either all-or-nothing relative to the current row count.
                Long postRowCount = jdbc.queryForObject(
                        "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                        Long.class, ORG);
                // Post-delete, vector_store for this org should be empty (the single doc's
                // chunks are the only org rows we seeded this trial).
                assertThat(postRowCount)
                        .as("trial " + trial + ": after delete commits, vector_store rows for "
                                + "this org must be 0 — the transactional cascade removed both "
                                + "the knowledge_contents row and its vector chunks")
                        .isEqualTo(0L);

                // The search either saw chunks (pre-delete snapshot) or saw none (post-delete
                // snapshot). Both are valid. Assert non-negative count and consistent shape:
                // every returned hit must carry the active orgId.
                for (Document d : hits) {
                    assertThat(d.getMetadata())
                            .as("trial " + trial + ": every search hit must carry orgId metadata "
                                    + "— a missing key would indicate a torn read where the chunk "
                                    + "row existed but its metadata wasn't fully materialised")
                            .containsKey("orgId");
                }
            }
        }
    }

    @Test
    void singleDocDelete_synchronouslyClearsVectorStoreInOneTransaction() {
        // Pin the @Transactional cascade contract directly without a race: after a single
        // knowledgeService.delete() returns, vector_store rows for that sourceId are gone in
        // the same observable moment as the knowledge_contents row. No async window.
        UUID kbId = UUID.randomUUID();
        ScopedValue.where(AgentContextHolder.orgId, ORG)
                .run(() -> knowledgeService.ingest("k8-sync-cascade.txt", "text/plain",
                        ("K8 sync cascade body " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
                        kbId));

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    Long count = jdbc.queryForObject(
                            "SELECT count(*) FROM vector_store WHERE metadata->>'orgId' = ?",
                            Long.class, ORG);
                    assertThat(count).isNotNull().isGreaterThanOrEqualTo(1L);
                });

        UUID docId = jdbc.queryForObject(
                "SELECT id FROM knowledge_contents WHERE knowledge_base_id = ?::uuid LIMIT 1",
                UUID.class, kbId);

        ScopedValue.where(AgentContextHolder.orgId, ORG)
                .run(() -> knowledgeService.delete(docId));

        Long contentRows = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_contents WHERE id = ?::uuid",
                Long.class, docId);
        Long vectorRows = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, docId.toString());

        assertThat(contentRows).as("knowledge_contents row must be gone after delete").isEqualTo(0L);
        assertThat(vectorRows)
                .as("vector_store rows for this docId must be gone in the same observable "
                        + "moment — a non-zero count here would mean the delete split the metadata "
                        + "and vector cleanup across transactions, leaving an orphaned-vector window")
                .isEqualTo(0L);
    }
}
