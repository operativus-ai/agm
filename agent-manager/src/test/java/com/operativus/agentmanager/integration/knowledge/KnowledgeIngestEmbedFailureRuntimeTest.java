package com.operativus.agentmanager.integration.knowledge;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModel;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Domain Responsibility: Pins the embedding-failure path for byte-array KB ingestion.
 *   {@code KnowledgeService.ingest} spawns a virtual thread that calls
 *   {@code vectorStore.add(chunks)} — pgvector's add() invokes the embedding model per
 *   batch. When the embedding API throws (timeout, 429, content-policy), the catch block
 *   must (1) flip {@code knowledge_contents.status=FAILED}, (2) populate
 *   {@code status_message}, (3) NOT half-commit chunks to {@code vector_store}, and
 *   (4) clear {@code content_hash} so the user can recover by re-uploading the same
 *   bytes — the {@code /retry} endpoint explicitly refuses file uploads, so re-upload
 *   is the ONLY recovery path.
 *
 *   <p><strong>R3 production fix shipped in this PR</strong>: previously the catch block
 *   left {@code content_hash} populated, so the partial unique index from migration 019
 *   ({@code uidx_knowledge_content_hash_kb WHERE content_hash IS NOT NULL}) blocked the
 *   re-upload via the {@code existsByContentHashAndKnowledgeBaseId} dedup check at
 *   {@code ingest()} entry. Users were locked out after a single embed failure. The fix
 *   clears {@code content_hash} on the FAILED transition; NULL is excluded from the
 *   partial index so the slot is released.</p>
 *
 *   Coordinated via the new {@code FakeEmbeddingModel#failNextCallWith} one-shot script
 *   added in this PR — the test stages a {@link RuntimeException} that the model throws on
 *   its next {@code call()} and then auto-clears so subsequent calls succeed normally
 *   (used by the recovery case to prove the re-upload works after a fail).
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class KnowledgeIngestEmbedFailureRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeEmbeddingModel fakeEmbedding;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM knowledge_contents");
        fakeEmbedding.reset();
    }

    @Test
    void embedFailureMidIngest_landsDocumentInFailedStatusWithoutHalfCommittedChunks() {
        HttpHeaders auth = authenticatedHeaders("kb-embed-fail");
        String kbId = createKnowledgeBase(auth, "K3 embed-fail KB");

        // Stage the failure BEFORE the upload so the VT's first embed call hits it.
        fakeEmbedding.failNextCallWith(
                new RuntimeException("simulated embedding API timeout"));

        byte[] fileBytes = ("K3 embed failure probe — content that would normally chunk and "
                + "embed cleanly, but the FakeEmbeddingModel is scripted to throw on the next "
                + "call() so the ingest VT hits the catch block.").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map<String, Object>> resp = postBatch(auth, kbId,
                List.of(filePart("k3-fail.txt", "text/plain", fileBytes)));

        assertEquals(202, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<String> accepted = (List<String>) resp.getBody().get("accepted");
        assertThat(accepted)
                .as("controller still accepts the file synchronously — the failure happens "
                        + "asynchronously on the VT, so the response is 202 + accepted=[docId]")
                .hasSize(1);
        String docId = accepted.get(0);

        // Poll for the async catch block to flip status to FAILED.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM knowledge_contents WHERE id = ?::uuid",
                            String.class, docId);
                    assertEquals("FAILED", status,
                            "embedding throw must drive status -> FAILED via the catch block; "
                                    + "PROCESSING here would mean the catch handler didn't fire "
                                    + "or didn't reach the save()");
                });

        String statusMessage = jdbc.queryForObject(
                "SELECT status_message FROM knowledge_contents WHERE id = ?::uuid",
                String.class, docId);
        assertNotNull(statusMessage,
                "status_message must be populated on FAILED so operators can diagnose the cause");
        assertThat(statusMessage).contains("Ingestion failed").contains("simulated embedding API timeout");

        // R3 production fix invariant: content_hash MUST be cleared so the partial unique
        // index releases the (hash, kb_id) slot. Without this clearing, the next
        // retry-by-reupload would be blocked by the dedup check at ingest() entry.
        String persistedHash = jdbc.queryForObject(
                "SELECT content_hash FROM knowledge_contents WHERE id = ?::uuid",
                String.class, docId);
        assertNull(persistedHash,
                "R3 fix: content_hash MUST be NULL on the FAILED row so the user can retry "
                        + "by re-uploading the same bytes — the /retry endpoint refuses file "
                        + "uploads, so re-upload is the only recovery path");

        // No partial chunks may have landed: pgvector's add() either commits all or none.
        Long chunkCount = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, docId);
        assertEquals(0L, chunkCount,
                "FAILED documents must have zero vector_store rows — a non-zero count means "
                        + "the embedding API failed AFTER some chunks committed, leaving the "
                        + "row inconsistent");
    }

    @Test
    void afterEmbedFailure_reUploadingSameBytesSucceeds_R3ProductionFix() {
        HttpHeaders auth = authenticatedHeaders("kb-embed-recover");
        String kbId = createKnowledgeBase(auth, "K3 recover KB");

        byte[] fileBytes = ("K3 recovery payload — identical bytes will be uploaded twice: "
                + "the first attempt fails on embed, the second must succeed because the FAILED "
                + "row clears content_hash and the partial unique index releases the slot.")
                .getBytes(StandardCharsets.UTF_8);

        // Attempt 1: fails on embed.
        fakeEmbedding.failNextCallWith(
                new RuntimeException("simulated transient 429"));
        ResponseEntity<Map<String, Object>> first = postBatch(auth, kbId,
                List.of(filePart("k3-recover.txt", "text/plain", fileBytes)));
        @SuppressWarnings("unchecked")
        List<String> firstAccepted = (List<String>) first.getBody().get("accepted");
        assertThat(firstAccepted).hasSize(1);
        String firstDocId = firstAccepted.get(0);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM knowledge_contents WHERE id = ?::uuid",
                            String.class, firstDocId);
                    assertEquals("FAILED", status,
                            "first attempt must land FAILED so the recovery path is exercised");
                });

        // Attempt 2: same bytes, no scripted failure → must succeed because content_hash was
        // cleared on the FAILED row, freeing the partial-unique-index slot.
        ResponseEntity<Map<String, Object>> second = postBatch(auth, kbId,
                List.of(filePart("k3-recover-retry.txt", "text/plain", fileBytes)));
        @SuppressWarnings("unchecked")
        List<String> secondAccepted = (List<String>) second.getBody().get("accepted");
        @SuppressWarnings("unchecked")
        List<String> secondRejected = (List<String>) second.getBody().get("rejected");

        assertThat(secondRejected)
                .as("second upload MUST NOT be rejected — a 'Duplicate file' rejection here "
                        + "would mean R3 fix regressed and the FAILED row's content_hash is "
                        + "still locking out re-upload")
                .isEmpty();
        assertThat(secondAccepted)
                .as("second upload (same bytes after a failed first attempt) must be accepted")
                .hasSize(1);
        String secondDocId = secondAccepted.get(0);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM knowledge_contents WHERE id = ?::uuid",
                            String.class, secondDocId);
                    assertEquals("COMPLETED", status,
                            "second upload must complete normally — the recovery path is "
                                    + "intact end-to-end");
                });

        Long chunkCount = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, secondDocId);
        assertThat(chunkCount)
                .as("chunks for the SUCCESSFUL retry must land in vector_store")
                .isNotNull().isGreaterThan(0L);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String createKnowledgeBase(HttpHeaders auth, String name) {
        Map<String, Object> kbBody = Map.of("name", name + " " + UUID.randomUUID(),
                "description", "K3 fixture");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST,
                new HttpEntity<>(kbBody, auth), JSON_MAP);
        return (String) kbCreated.getBody().get("id");
    }

    private ResponseEntity<Map<String, Object>> postBatch(
            HttpHeaders auth, String kbId, List<ByteArrayResource> filePartList) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (ByteArrayResource part : filePartList) {
            body.add("files", part);
        }
        body.add("knowledgeBaseId", kbId);

        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.addAll(auth);
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        return rest.exchange(url("/api/knowledge/upload-batch"), HttpMethod.POST,
                new HttpEntity<>(body, multipartHeaders), JSON_MAP);
    }

    private static ByteArrayResource filePart(String filename, String contentType, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-kb-k3-1234",
                List.of("ROLE_USER"));
    }
}
