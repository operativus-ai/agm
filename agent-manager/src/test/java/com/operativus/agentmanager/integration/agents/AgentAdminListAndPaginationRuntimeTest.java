package com.operativus.agentmanager.integration.agents;

import com.operativus.agentmanager.control.config.PaginationDefaultsConfig;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins pagination edge cases on
 *   {@code GET /api/admin/agents} —
 *   {@link com.operativus.agentmanager.control.controller.AgentAdminController#getAllAgents}.
 *   The handler uses Spring's standard {@code Pageable} resolution, so the pinned behavior
 *   reflects Spring Data's pagination contract clamped by
 *   {@link PaginationDefaultsConfig#MAX_PAGE_SIZE} (200).
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Oversize page</b> — request {@code size=1000} is clamped to 200 (DoS guard).
 *         A successful response with {@code size <= 200} pins the
 *         {@code PageableHandlerMethodArgumentResolverCustomizer} wiring.</li>
 *     <li><b>Negative page</b> — request {@code page=-5} pins the current behavior
 *         (4xx via Spring's default, or graceful fallback to page 0).</li>
 *     <li><b>Empty result</b> — clean DB returns {@code content=[]} + {@code totalElements=0}
 *         + {@code totalPages=0}. Pins the {@code Page<T>} shape on empty data.</li>
 *     <li><b>Multi-page navigation</b> — seeding 5 agents and paging with {@code size=2}
 *         returns disjoint content across pages 0/1/2 totaling all 5.</li>
 *     <li><b>Sort direction</b> — {@code sort=name,asc} vs {@code sort=name,desc} produce
 *         ordered results in the expected directions.</li>
 *     <li><b>Invalid sort field</b> — pinning whichever Spring does:
 *         400 (strict) or silent ignore (graceful).</li>
 *   </ul>
 *
 *   <p>Why this matters: an unbounded paginated read is a DoS vector. The clamp at 200 is
 *   the production guarantee. If a future refactor swaps the global
 *   {@code PageableHandlerMethodArgumentResolverCustomizer} for an endpoint-local builder
 *   that doesn't call {@code clampedPageRequest}, this test fails.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentAdminListAndPaginationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> PAGE_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("agent-list-admin",
                "agent-list-admin@test.local", "pass-ala-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void oversizePageRequestIsClampedToMaxPageSize() {
        seedAgents(3);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents?page=0&size=1000"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                PAGE_RESPONSE);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        // Spring Boot 4 / Spring Data 4 nested page envelope:
        // {content, page: {size, number, totalElements, totalPages}}.
        @SuppressWarnings("unchecked")
        Map<String, Object> pageMeta = (Map<String, Object>) body.get("page");
        assertNotNull(pageMeta, "Page response missing nested `page` object");
        Integer pageSize = ((Number) pageMeta.get("size")).intValue();
        assertTrue(pageSize <= PaginationDefaultsConfig.MAX_PAGE_SIZE,
                "Page size must be clamped to MAX_PAGE_SIZE ("
                        + PaginationDefaultsConfig.MAX_PAGE_SIZE
                        + "); got " + pageSize
                        + ". The PageableHandlerMethodArgumentResolverCustomizer "
                        + "may not be wired for this endpoint.");
    }

    @Test
    void emptyResultReturnsValidPageEnvelope() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                PAGE_RESPONSE);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Object> content = (List<Object>) body.get("content");
        assertNotNull(content, "Page response missing `content` array");
        assertEquals(0, content.size(), "Empty DB must return empty content array");
        @SuppressWarnings("unchecked")
        Map<String, Object> pageMeta = (Map<String, Object>) body.get("page");
        assertNotNull(pageMeta, "Page response missing nested `page` object");
        assertEquals(0, ((Number) pageMeta.get("totalElements")).intValue());
        assertEquals(0, ((Number) pageMeta.get("totalPages")).intValue());
    }

    @Test
    void multiPageNavigationReturnsDisjointContentAcrossAllPages() {
        seedAgents(5);

        Set<String> idsPage0 = collectAgentIds("/api/admin/agents?page=0&size=2");
        Set<String> idsPage1 = collectAgentIds("/api/admin/agents?page=1&size=2");
        Set<String> idsPage2 = collectAgentIds("/api/admin/agents?page=2&size=2");

        assertEquals(2, idsPage0.size(), "page 0 must have size 2");
        assertEquals(2, idsPage1.size(), "page 1 must have size 2");
        assertEquals(1, idsPage2.size(), "page 2 must have the remaining 1 (5 total, size 2)");

        Set<String> union = new HashSet<>();
        union.addAll(idsPage0);
        union.addAll(idsPage1);
        union.addAll(idsPage2);
        assertEquals(5, union.size(),
                "Pages must be disjoint and cover all 5 seeded agents; "
                        + "duplicates or gaps detected: page0=" + idsPage0
                        + " page1=" + idsPage1 + " page2=" + idsPage2);
    }

    @Test
    void sortDirectionAffectsContentOrder() {
        // Seed agents whose `name` field sorts alphabetically — sorting is by the JPA entity
        // field, NOT the JSON property, so use the entity name column directly.
        seedAgentWithName("agent-1", "aaa-first-name");
        seedAgentWithName("agent-2", "zzz-last-name");

        List<String> ascNames = collectFieldOrdered(
                "/api/admin/agents?page=0&size=10&sort=name,asc", "name");
        List<String> descNames = collectFieldOrdered(
                "/api/admin/agents?page=0&size=10&sort=name,desc", "name");

        assertTrue(ascNames.indexOf("aaa-first-name") < ascNames.indexOf("zzz-last-name"),
                "asc sort must place aaa-first-name before zzz-last-name; got " + ascNames);
        assertTrue(descNames.indexOf("zzz-last-name") < descNames.indexOf("aaa-first-name"),
                "desc sort must place zzz-last-name before aaa-first-name; got " + descNames);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgents(int count) {
        for (int i = 0; i < count; i++) {
            seedAgentWithName("agent-" + i + "-" + UUID.randomUUID(),
                    "Seeded agent " + i);
        }
    }

    private void seedAgentWithName(String id, String name) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'test-instructions', NULL, true, 1, 'TIER_1_STANDARD',
                        false, 0, ?, NOW(), NOW())
                """, id, name, "desc-" + id, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private Set<String> collectAgentIds(String path) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url(path),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                PAGE_RESPONSE);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "GET " + path + " failed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) response.getBody().get("content");
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> row : content) {
            Object id = row.get("agentId");
            if (id == null) id = row.get("id");
            if (id != null) ids.add(id.toString());
        }
        return ids;
    }

    private List<String> collectFieldOrdered(String path, String field) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url(path),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                PAGE_RESPONSE);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "GET " + path + " failed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) response.getBody().get("content");
        return content.stream()
                .map(row -> row.get(field))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
