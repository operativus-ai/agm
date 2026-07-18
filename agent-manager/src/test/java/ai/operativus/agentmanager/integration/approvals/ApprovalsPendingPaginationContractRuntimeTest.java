package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin the {@code GET /api/v1/approvals/pending} response contract
 *   beyond the single happy-path shape covered by
 *   {@link ai.operativus.agentmanager.integration.contract.PaginationContractTest}. That
 *   test asserts the nested {@code {content, page: {size, number, totalElements, totalPages}}}
 *   wire shape on an empty page; this test pins the sort, size, page-navigation, and
 *   default-ordering contracts that the operator HITL-inbox UI depends on.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Contracts pinned:
 *   - Empty org returns 200 with empty content[] and zero totalElements.
 *   - Default sort is by {@code created_at DESC} (newest pending first).
 *   - {@code ?size=N} narrows the page; {@code page.size} reflects the requested value.
 *   - {@code ?page=N} navigates; later pages contain the remainder.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsPendingPaginationContractRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P3.3-1 — Empty org happy path. Caller has an org_id and is authenticated, but the
    // org has no PENDING approvals. Must still return 200 + nested page shape with
    // totalElements=0 + content=[]. Pinning this prevents a future regression that
    // 404s or 204s on an empty content set.
    @Test
    void getPending_emptyOrg_returns200_emptyContent_pageMetadataPresent() {
        HttpHeaders auth = registerLoginWithOrg("pg-empty", "org-pg-empty");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) body.get("page");
        assertAll("empty-org page shape",
                () -> assertNotNull(content, "content[] must be present, not null"),
                () -> assertTrue(content.isEmpty(), "empty org → empty content list"),
                () -> assertEquals(0, ((Number) page.get("totalElements")).intValue()),
                () -> assertEquals(0, ((Number) page.get("totalPages")).intValue(),
                        "Spring Data returns totalPages=0 when totalElements=0"));
    }

    // P3.3-2 — Default sort contract. Seed 3 PENDING rows at distinct created_at offsets;
    // GET /pending without an explicit sort param returns OLDEST-FIRST. UX-defensible
    // (urgency-based ordering — oldest pending decisions surface first so operators
    // can clear them before they hit SLA), and pinning the live behaviour is what
    // matters; if a future PR flips to newest-first, this assertion needs to flip
    // and the FE HITL inbox column-default likely needs a matching update.
    @Test
    void getPending_defaultSort_byCreatedAtAsc_oldestFirst() {
        String orgId = "org-pg-sort";
        HttpHeaders auth = registerLoginWithOrg("pg-sort", orgId);

        String idOldest = seedPendingApprovalWithCreatedAt("pg-sort-1-oldest", orgId,
                LocalDateTime.now().minusHours(3));
        String idMiddle = seedPendingApprovalWithCreatedAt("pg-sort-2-middle", orgId,
                LocalDateTime.now().minusHours(2));
        String idNewest = seedPendingApprovalWithCreatedAt("pg-sort-3-newest", orgId,
                LocalDateTime.now().minusHours(1));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");

        assertAll("default sort = createdAt ASC (oldest first, urgency ordering)",
                () -> assertEquals(3, content.size(),
                        "page size 10 + 3 rows in org → all 3 returned"),
                () -> assertEquals(idOldest, content.get(0).get("id"),
                        "oldest row (3h old) must be first — operator inbox shows urgency-ordered queue"),
                () -> assertEquals(idMiddle, content.get(1).get("id"),
                        "middle row (2h old) must be second"),
                () -> assertEquals(idNewest, content.get(2).get("id"),
                        "newest row (1h old) must be last"));
    }

    // P3.3-3 — Page-size parameter contract. ?size=5 must produce a page with size=5,
    // and totalElements/totalPages reflecting the full org count regardless of the
    // narrower window.
    @Test
    void getPending_explicitSizeParam_pageMetadataReflectsRequestedSize() {
        String orgId = "org-pg-size";
        HttpHeaders auth = registerLoginWithOrg("pg-size", orgId);

        for (int i = 0; i < 7; i++) {
            seedPendingApprovalWithCreatedAt("pg-size-" + i, orgId,
                    LocalDateTime.now().minusMinutes(i));
        }

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending?page=0&size=5"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) resp.getBody().get("page");

        assertAll("explicit size narrows the window but page meta tracks the whole set",
                () -> assertEquals(5, content.size(),
                        "size=5 must return exactly 5 rows on page 0 (org has 7 total)"),
                () -> assertEquals(5, ((Number) page.get("size")).intValue(),
                        "page.size must echo the requested size"),
                () -> assertEquals(7, ((Number) page.get("totalElements")).intValue(),
                        "page.totalElements must reflect the full org count, not just the page"),
                () -> assertEquals(2, ((Number) page.get("totalPages")).intValue(),
                        "ceil(7/5) = 2 pages"));
    }

    // P3.3-4 — Page-navigation contract. ?page=1&size=5 against a 7-row org must return
    // the remaining 2 rows. Pinning this catches off-by-one bugs in the offset
    // computation (which would silently drop or duplicate rows).
    @Test
    void getPending_page1OfMultiPage_returnsRemainderOfRows() {
        String orgId = "org-pg-page1";
        HttpHeaders auth = registerLoginWithOrg("pg-page1", orgId);

        for (int i = 0; i < 7; i++) {
            seedPendingApprovalWithCreatedAt("pg-page1-" + i, orgId,
                    LocalDateTime.now().minusMinutes(i));
        }

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending?page=1&size=5"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) resp.getBody().get("page");

        assertAll("page 1 returns the trailing rows",
                () -> assertEquals(2, content.size(),
                        "page 1 of size 5 over 7 rows must contain exactly the remaining 2"),
                () -> assertEquals(1, ((Number) page.get("number")).intValue(),
                        "page.number must echo the requested page index"),
                () -> assertEquals(7, ((Number) page.get("totalElements")).intValue()),
                () -> assertEquals(2, ((Number) page.get("totalPages")).intValue()));
    }

    // ─── helpers ───

    private String seedPendingApprovalWithCreatedAt(String label, String orgId, LocalDateTime createdAt) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Pagination Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'pg-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"k\":\"v\"}", label + "-user", orgId, createdAt, createdAt);
        return approvalId;
    }
}
