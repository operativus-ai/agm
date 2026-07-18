package com.operativus.agentmanager.integration.knowledge;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Domain Responsibility: Black-box coverage of the multipart file upload surface —
 *   {@code POST /api/knowledge/upload-batch} → {@code KnowledgeController.uploadBatch} →
 *   {@code KnowledgeService.ingest(filename, contentType, bytes, kbId, description)}.
 *   The byte-array {@code ingest} path is distinct from the URL ingestion path pinned by
 *   {@code KnowledgeBaseRuntimeTest.ingestByUrl_*} and the Resource-based path pinned by
 *   {@code KnowledgeBaseRuntimeTest.ingestByText_hashDedupPreventsDuplicates} — until this
 *   class, the multipart-form-data wire surface had no runtime test.
 *
 *   Cases pinned:
 *   <ol>
 *     <li>Single text file → 202 + {@code accepted=[docId]}, row lands in
 *         {@code knowledge_contents} with COMPLETED status, chunks appear in
 *         {@code vector_store} with metadata.sourceId = documentId.</li>
 *     <li>Mixed batch (valid + empty) → partial-success contract: valid file accepted,
 *         empty file appears in {@code rejected} with reason "empty".</li>
 *     <li>Empty file only → 202 + zero accepted, no DB row.</li>
 *     <li>Duplicate-content second upload to same KB → rejected by SHA-256 hash dedup
 *         (per migration 019).</li>
 *     <li>Larger multi-chunk text → multiple chunks persisted with distinct metadata.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class KnowledgeMultipartUploadRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        // Hash-dedup is per-(content_hash, knowledge_base_id); clear knowledge_contents
        // before each test so the dedup case can stage a clean first-upload baseline.
        jdbc.update("DELETE FROM knowledge_contents");
    }

    @Test
    void uploadSingleTextFile_returns202_persistsDocumentAndChunks() {
        HttpHeaders auth = authenticatedHeaders("kb-mp-single");
        String kbId = createKnowledgeBase(auth, "K2 single-file KB");

        byte[] fileBytes = "K2 happy path multipart upload — quick brown fox over the lazy dog."
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map<String, Object>> resp = postBatch(auth, kbId,
                List.of(filePart("k2-single.txt", "text/plain", fileBytes)));

        assertEquals(202, resp.getStatusCode().value(),
                "POST /api/knowledge/upload-batch must return 202 ACCEPTED on partial-or-full success");
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> accepted = (List<String>) body.get("accepted");
        @SuppressWarnings("unchecked")
        List<String> rejected = (List<String>) body.get("rejected");
        assertThat(accepted).hasSize(1);
        assertThat(rejected).isEmpty();
        String docId = accepted.get(0);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM knowledge_contents WHERE id = ?::uuid",
                            String.class, docId);
                    assertEquals("COMPLETED", status,
                            "byte-array ingest must land knowledge_contents.status=COMPLETED");
                });

        Long chunkCount = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, docId);
        assertThat(chunkCount).isNotNull().isGreaterThan(0L);

        String persistedName = jdbc.queryForObject(
                "SELECT name FROM knowledge_contents WHERE id = ?::uuid",
                String.class, docId);
        assertEquals("k2-single.txt", persistedName,
                "controller must persist the original filename from the multipart part");
    }

    @Test
    void uploadMixedBatch_acceptsValidAndRejectsEmpty() {
        HttpHeaders auth = authenticatedHeaders("kb-mp-mixed");
        String kbId = createKnowledgeBase(auth, "K2 mixed-batch KB");

        ResponseEntity<Map<String, Object>> resp = postBatch(auth, kbId, List.of(
                filePart("k2-valid.txt", "text/plain",
                        "valid content for mixed batch".getBytes(StandardCharsets.UTF_8)),
                filePart("k2-empty.txt", "text/plain", new byte[0])));

        assertEquals(202, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<String> accepted = (List<String>) resp.getBody().get("accepted");
        @SuppressWarnings("unchecked")
        List<String> rejected = (List<String>) resp.getBody().get("rejected");

        assertThat(accepted).as("valid file must be accepted").hasSize(1);
        assertThat(rejected)
                .as("empty file must be rejected with reason 'empty' — partial-success contract")
                .hasSize(1)
                .anySatisfy(r -> assertThat(r).contains("k2-empty.txt").contains("empty"));

        // The accepted document MUST persist; the rejected one MUST NOT.
        Long contentCount = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_contents WHERE knowledge_base_id = ?::uuid",
                Long.class, kbId);
        assertEquals(1L, contentCount,
                "only the valid file may persist — partial success must not over-commit");
    }

    @Test
    void uploadEmptyFileOnly_returns202WithRejectionAndNoDbRow() {
        HttpHeaders auth = authenticatedHeaders("kb-mp-empty-only");
        String kbId = createKnowledgeBase(auth, "K2 empty-only KB");

        ResponseEntity<Map<String, Object>> resp = postBatch(auth, kbId,
                List.of(filePart("k2-only-empty.txt", "text/plain", new byte[0])));

        assertEquals(202, resp.getStatusCode().value(),
                "all-rejected still returns 202 — the controller does not 4xx because a single "
                        + "bad file is rejected; the rejected list is the channel for failures");
        @SuppressWarnings("unchecked")
        List<String> accepted = (List<String>) resp.getBody().get("accepted");
        @SuppressWarnings("unchecked")
        List<String> rejected = (List<String>) resp.getBody().get("rejected");
        assertThat(accepted).isEmpty();
        assertThat(rejected).hasSize(1)
                .anySatisfy(r -> assertThat(r).contains("empty"));

        Long contentCount = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_contents WHERE knowledge_base_id = ?::uuid",
                Long.class, kbId);
        assertEquals(0L, contentCount,
                "rejected-only upload must persist zero rows");
    }

    @Test
    void uploadDuplicateContent_secondIsRejectedByHashDedup() {
        HttpHeaders auth = authenticatedHeaders("kb-mp-dedup");
        String kbId = createKnowledgeBase(auth, "K2 dedup KB");

        byte[] fileBytes = "K2 dedup payload — SHA-256 must collide on a second identical upload."
                .getBytes(StandardCharsets.UTF_8);

        // First upload: lands cleanly
        ResponseEntity<Map<String, Object>> first = postBatch(auth, kbId,
                List.of(filePart("k2-dedup-a.txt", "text/plain", fileBytes)));
        @SuppressWarnings("unchecked")
        List<String> firstAccepted = (List<String>) first.getBody().get("accepted");
        assertThat(firstAccepted).hasSize(1);

        // Second upload: same bytes, different filename, same KB → must be rejected by
        // the SHA-256 dedup in KnowledgeService.ingest (migration 019). The reject reason
        // surfaces the BusinessValidationException message "Duplicate file: ...".
        ResponseEntity<Map<String, Object>> second = postBatch(auth, kbId,
                List.of(filePart("k2-dedup-b.txt", "text/plain", fileBytes)));
        @SuppressWarnings("unchecked")
        List<String> secondAccepted = (List<String>) second.getBody().get("accepted");
        @SuppressWarnings("unchecked")
        List<String> secondRejected = (List<String>) second.getBody().get("rejected");

        assertThat(secondAccepted).as("second upload must be rejected by dedup").isEmpty();
        assertThat(secondRejected)
                .hasSize(1)
                .anySatisfy(r -> assertThat(r).contains("k2-dedup-b.txt")
                        .containsIgnoringCase("duplicate"));

        // Exactly one row persists despite two upload attempts with identical content.
        Long contentCount = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_contents WHERE knowledge_base_id = ?::uuid",
                Long.class, kbId);
        assertEquals(1L, contentCount,
                "hash dedup must keep the count at 1 — a second row would mean migration 019's "
                        + "(content_hash, knowledge_base_id) unique index regressed");
    }

    @Test
    void uploadLargeMultiChunkFile_persistsMultipleChunks() {
        HttpHeaders auth = authenticatedHeaders("kb-mp-large");
        String kbId = createKnowledgeBase(auth, "K2 large-file KB");

        // Generate ~6 KB of distinct content so TokenTextSplitter produces multiple chunks.
        StringBuilder sb = new StringBuilder(8 * 1024);
        for (int i = 0; i < 400; i++) {
            sb.append("Paragraph ").append(i)
                    .append(": the K2 large-file ingestion test stresses the TokenTextSplitter ")
                    .append("by feeding a document well above the default chunk boundary so the ")
                    .append("ingest path produces multiple vector_store rows for one document. ");
        }
        byte[] largeBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map<String, Object>> resp = postBatch(auth, kbId,
                List.of(filePart("k2-large.txt", "text/plain", largeBytes)));

        @SuppressWarnings("unchecked")
        List<String> accepted = (List<String>) resp.getBody().get("accepted");
        assertThat(accepted).hasSize(1);
        String docId = accepted.get(0);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM knowledge_contents WHERE id = ?::uuid",
                            String.class, docId);
                    assertEquals("COMPLETED", status,
                            "large-file ingest must complete; FAILED here would mean the embed "
                                    + "call or the splitter regressed");
                });

        Long chunkCount = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Long.class, docId);
        assertThat(chunkCount)
                .as("a ~6KB document must produce at least 2 chunks under TokenTextSplitter "
                        + "defaults — a value of 1 would indicate the splitter never ran")
                .isNotNull().isGreaterThanOrEqualTo(2L);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String createKnowledgeBase(HttpHeaders auth, String name) {
        Map<String, Object> kbBody = Map.of("name", name + " " + UUID.randomUUID(),
                "description", "K2 fixture");
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

    /**
     * ByteArrayResource subclass that returns the desired filename from
     * {@link #getFilename()} so Spring's multipart converter sends a
     * {@code Content-Disposition: form-data; name="files"; filename="..."} header that
     * Tomcat parses into {@code MultipartFile.getOriginalFilename()}. Without overriding
     * {@code getFilename()}, the part has no filename and the BE field stays null.
     */
    private static ByteArrayResource filePart(String filename, String contentType, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-kb-mp-1234",
                List.of("ROLE_USER"));
    }
}
