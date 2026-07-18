package ai.operativus.agentmanager.integration.knowledge;

import ai.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import ai.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import ai.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the knowledge_bases surface.
 *   Pins desired contracts §3.7 of {@code docs/plans/agm-knowledge-tenant-isolation.md}:
 *   ADMIN-of-org-A cannot see, fetch, modify, or delete ADMIN-of-org-B's knowledge bases or
 *   their contents. Cross-tenant lookups return 404 (NOT 403) — existence-leak protection.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Sibling tests (same domain, different concerns):
 *   - {@link KnowledgeBaseRuntimeTest} — happy-path CRUD + ingestion behavior
 *   - {@link KnowledgePreviewRuntimeTest} — single-doc preview + chunks/detail wire shape
 *     (cross-tenant 404 there is the same pattern, exercised in this class via the
 *     {@code preview404ForCrossTenantOwnedDocument} case).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class KnowledgeBaseTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void listReturnsOnlyCallerOrgKnowledgeBases() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-list", "kb-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("kb-iso-b-list", "kb-iso-org-B");

        createKb(orgA, "A's KB Alpha");
        createKb(orgA, "A's KB Beta");
        createKb(orgB, "B's KB Gamma");

        // org A's listing must contain only A's rows
        ResponseEntity<List<Map<String, Object>>> aList = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_LIST);
        assertEquals(HttpStatus.OK, aList.getStatusCode());
        List<Map<String, Object>> aBody = aList.getBody();
        assertNotNull(aBody);
        assertEquals(2, aBody.size(),
                "org A listing must contain exactly A's 2 KBs; got " + aBody.size());
        assertTrue(aBody.stream().allMatch(kb -> "kb-iso-org-A".equals(kb.get("orgId"))),
                "every row in A's listing must be tagged orgId=kb-iso-org-A; got " + aBody);

        // org B's listing must contain only B's rows
        ResponseEntity<List<Map<String, Object>>> bList = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_LIST);
        assertEquals(HttpStatus.OK, bList.getStatusCode());
        List<Map<String, Object>> bBody = bList.getBody();
        assertEquals(1, bBody.size(),
                "org B listing must contain exactly B's 1 KB; got " + bBody.size());
        assertTrue(bBody.stream().allMatch(kb -> "kb-iso-org-B".equals(kb.get("orgId"))),
                "every row in B's listing must be tagged orgId=kb-iso-org-B; got " + bBody);
    }

    // NOTE: a former `getById404ForCrossTenantKb` test was deleted as a tautology — there
    // is no `GET /api/v1/knowledge-bases/{id}` endpoint on KnowledgeBaseController, so the
    // 404 it asserted came from Spring's default route-not-found behaviour and would have
    // passed against any UUID, including the caller's own KBs. Cross-tenant readability
    // is still pinned indirectly: A cannot UPDATE, DELETE, list, or fetch /{id}/agents
    // on B's KBs (see the other tests in this class plus KnowledgeBaseEdgeCasesRuntimeTest).

    @Test
    void update404ForCrossTenantKb() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-put", "kb-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("kb-iso-b-put", "kb-iso-org-B");

        String bKbId = createKb(orgB, "B's KB Update-Probe");

        // name is @NotBlank on KnowledgeBaseRequest (#1017) — include it so the body passes @Valid
        // and reaches the cross-tenant ownership check (404), not a 400 validation bounce.
        Map<String, Object> updateBody = Map.of("name", "B KB rename attempt", "description", "should never apply");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/knowledge-bases/" + bKbId),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "PUT cross-tenant must return 404; got " + response.getStatusCode());

        // Verify the row's description was NOT modified.
        String bDescription = jdbc.queryForObject(
                "SELECT description FROM knowledge_bases WHERE id = ?::uuid",
                String.class, bKbId);
        assertEquals("description for B's KB Update-Probe", bDescription,
                "cross-tenant PUT must not have written B's row; got description=" + bDescription);
    }

    @Test
    void delete404ForCrossTenantKb() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-del", "kb-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("kb-iso-b-del", "kb-iso-org-B");

        String bKbId = createKb(orgB, "B's KB Delete-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/knowledge-bases/" + bKbId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "DELETE cross-tenant must return 404 (no deletion job enqueued); got "
                        + response.getStatusCode());

        // Verify B's row still exists — no deletion job was kicked off.
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_bases WHERE id = ?::uuid",
                Long.class, bKbId);
        assertEquals(1L, count == null ? 0L : count,
                "cross-tenant DELETE must not have enqueued a deletion job for B's row");
    }

    @Test
    void preview404ForCrossTenantOwnedDocument() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-prev", "kb-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("kb-iso-b-prev", "kb-iso-org-B");

        String bKbIdStr = createKb(orgB, "B's KB Preview-Probe");
        UUID bKbId = UUID.fromString(bKbIdStr);
        UUID bDocId = UUID.randomUUID();
        seedKnowledgeContent(bDocId, bKbId, "b-doc.txt", "COMPLETED");

        // org A asking for B's doc preview must get 404 (not 403, not 200).
        ResponseEntity<String> previewResponse = rest.exchange(
                url("/api/knowledge/" + bDocId + "/preview"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, previewResponse.getStatusCode(),
                "preview cross-tenant must return 404; got " + previewResponse.getStatusCode());

        // Same for chunks/detail.
        ResponseEntity<String> chunksResponse = rest.exchange(
                url("/api/knowledge/" + bDocId + "/chunks/detail"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, chunksResponse.getStatusCode(),
                "chunks/detail cross-tenant must return 404; got " + chunksResponse.getStatusCode());

        // Sanity: org B itself can preview the doc.
        ResponseEntity<Map<String, Object>> bPreview = rest.exchange(
                url("/api/knowledge/" + bDocId + "/preview"),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_MAP);
        assertEquals(HttpStatus.OK, bPreview.getStatusCode(),
                "B's own preview must succeed; got " + bPreview.getStatusCode());
    }

    @Test
    void postIgnoresBodyOrgIdAndStampsCallerOrgId() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-post", "kb-iso-org-A");

        // Caller is in org A; the body lies and claims to be in org B.
        Map<String, Object> body = new HashMap<>();
        body.put("name", "post-org-injection-attempt");
        body.put("description", "body claims org B");
        body.put("orgId", "kb-iso-org-B");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.POST,
                new HttpEntity<>(body, orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> created = response.getBody();
        assertNotNull(created);

        // The persisted row's orgId must be A's (caller-derived), not B's (body's claim).
        String createdId = String.valueOf(created.get("id"));
        String storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM knowledge_bases WHERE id = ?::uuid",
                String.class, createdId);
        assertEquals("kb-iso-org-A", storedOrgId,
                "POST must stamp caller's orgId; body-injected orgId must be ignored. got=" + storedOrgId);
    }

    @Test
    void listFiles404ForCrossTenantKb() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-files-by-kb", "kb-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("kb-iso-b-files-by-kb", "kb-iso-org-B");

        String bKbIdStr = createKb(orgB, "B's KB Files-Probe");
        UUID bKbId = UUID.fromString(bKbIdStr);
        seedKnowledgeContent(UUID.randomUUID(), bKbId, "b-secret-doc.txt", "COMPLETED");

        ResponseEntity<String> response = rest.exchange(
                url("/api/knowledge?knowledgeBaseId=" + bKbId),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /api/knowledge?knowledgeBaseId={B's UUID} from org A must 404; got "
                        + response.getStatusCode() + " body=" + response.getBody());
    }

    @Test
    void listFilesPagedReturnsOnlyCallerOrgDocs() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-files-paged", "kb-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("kb-iso-b-files-paged", "kb-iso-org-B");

        UUID aKbId = UUID.fromString(createKb(orgA, "A's KB Paged"));
        UUID bKbId = UUID.fromString(createKb(orgB, "B's KB Paged"));
        UUID aDocId = UUID.randomUUID();
        UUID bDocId = UUID.randomUUID();
        seedKnowledgeContent(aDocId, aKbId, "a-paged.txt", "COMPLETED");
        seedKnowledgeContent(bDocId, bKbId, "b-paged.txt", "COMPLETED");

        ResponseEntity<Map<String, Object>> aResp = rest.exchange(
                url("/api/knowledge?size=100"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, aResp.getStatusCode());
        Map<String, Object> aBody = aResp.getBody();
        assertNotNull(aBody);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContent = (List<Map<String, Object>>) aBody.get("content");
        assertNotNull(aContent, "page response must contain 'content' array; got " + aBody);
        assertTrue(aContent.stream().anyMatch(d -> aDocId.toString().equals(String.valueOf(d.get("id")))),
                "A's paged listing must include A's own doc; got " + aContent);
        assertTrue(aContent.stream().noneMatch(d -> bDocId.toString().equals(String.valueOf(d.get("id")))),
                "A's paged listing must NOT include B's doc; got " + aContent);
    }

    @Test
    void getById404ForCrossTenantDoc() {
        HttpHeaders orgA = registerLoginWithOrg("kb-iso-a-getbyid", "kb-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("kb-iso-b-getbyid", "kb-iso-org-B");

        UUID bKbId = UUID.fromString(createKb(orgB, "B's KB GetById-Probe"));
        UUID bDocId = UUID.randomUUID();
        seedKnowledgeContent(bDocId, bKbId, "b-getbyid.txt", "COMPLETED");

        ResponseEntity<String> response = rest.exchange(
                url("/api/knowledge/" + bDocId),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /api/knowledge/{B's docId} from org A must 404; got "
                        + response.getStatusCode() + " body=" + response.getBody());

        ResponseEntity<Map<String, Object>> bResp = rest.exchange(
                url("/api/knowledge/" + bDocId),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_MAP);
        assertEquals(HttpStatus.OK, bResp.getStatusCode(),
                "B's own getById must succeed; got " + bResp.getStatusCode());
    }

    @Test
    void assignAgent404ForCrossTenantAgentId_andBindingNotInserted() {
        // The controller checks the KB id belongs to caller's org, but pre-fix the
        // service used findById(agentId) with NO tenant check. Org A admin could
        // POST /api/v1/knowledge-bases/{A-kb-id}/agents/{B-agent-id} and attach
        // A's KB to B's agent — polluting B's RAG retrieval with A's content.
        HttpHeaders adminA = registerLoginWithOrg("kb-iso-a-assign", "kb-iso-org-A");
        HttpHeaders adminB = registerLoginWithOrg("kb-iso-b-assign", "kb-iso-org-B");

        // A's KB (caller owns it — controller-level guard passes)
        String aKbId = createKb(adminA, "A's Sensitive KB");
        // B's agent (different tenant; service-level guard must reject)
        String bAgentId = "agent-kb-iso-b-" + UUID.randomUUID();
        seedFakeModel();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, bAgentId, "B's victim agent", "kb-iso-org-B");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/knowledge-bases/" + aKbId + "/agents/" + bAgentId),
                HttpMethod.POST,
                new HttpEntity<>(adminA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /knowledge-bases/{A-kb}/agents/{B-agent} as A must return 404 "
                        + "(existence-leak protection); got " + response.getStatusCode());

        // B's agent.knowledge_base_ids must NOT contain A's KB id. Read the JSONB
        // column as text to assert the substring is absent.
        String bKnowledgeBaseIds = jdbc.queryForObject(
                "SELECT COALESCE(knowledge_base_ids::text, '[]') FROM agents WHERE id = ?",
                String.class, bAgentId);
        assertTrue(!bKnowledgeBaseIds.contains(aKbId),
                "B's agent.knowledge_base_ids must NOT contain A's KB id post-attempt; got "
                        + bKnowledgeBaseIds);
    }

    // ─── helpers ───

    private void seedFakeModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private String createKb(HttpHeaders auth, String name) {
        Map<String, Object> body = Map.of(
                "name", name,
                "description", "description for " + name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "createKb fixture must succeed; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private void seedKnowledgeContent(UUID id, UUID kbId, String name, String status) {
        jdbc.update("""
                INSERT INTO knowledge_contents
                  (id, name, description, content_type, uri, content_hash, size, status,
                   status_message, metadata, vector_ids, knowledge_base_id, owner_id, access_count,
                   created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::uuid, ?, ?, NOW(), NOW())
                """,
                id, name, "test fixture", "text/plain", "fixture://" + name,
                "hash-" + id, 42, status, null, "{}", new UUID[0], kbId, "kb-iso-test-owner", 0);
    }
}
