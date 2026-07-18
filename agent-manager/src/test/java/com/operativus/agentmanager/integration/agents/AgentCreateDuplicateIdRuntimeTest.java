package com.operativus.agentmanager.integration.agents;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the May-2026 agent-create chain (PRs #923, #924, #926).
 * In-process equivalent of {@code scripts/walkthroughs/14-agent-create-duplicate-id-409.sh}
 * — verifies the three contract assertions the walkthrough probes against a live BE:
 *
 *   - PR #924 — {@code AgentAdminService.createAgent} sets {@code entity.setVersion(null)}
 *     so {@code agentRepository.save()} routes new entities through {@code em.persist()}
 *     (INSERT). Pre-fix the {@code @Version=0} default routed save() to {@code em.merge()},
 *     which on a brand-new PK affects 0 rows and Hibernate raised
 *     {@code StaleObjectStateException} → false-positive 409 every time on a fresh id.
 *     Pin: POST a fresh id returns 201 AND the persisted row has {@code version=0}.
 *
 *   - PR #926 — {@code GET /api/admin/agents/{id}} endpoint exists.
 *     Pre-fix only sub-resource paths existed; a sanity probe by id returned 404.
 *     Pin: after POST 201, GET /{id} returns 200 with the persisted body.
 *
 *   - PR #923 — {@code GlobalExceptionHandler} maps {@code DataIntegrityViolationException}
 *     to 409 with a {@code hint} property carrying the offending constraint name. Pre-fix
 *     the catch-all returned 500 with no diagnostic for duplicate POSTs.
 *     Pin: a duplicate POST returns 409 AND the response body carries {@code hint}.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Why this test exists alongside {@link AgentsCrudRuntimeTest}: that class verifies the
 * happy-path CRUD shape (§5.1) and validation failure (§5.2) but does NOT exercise the
 * duplicate-id collision path, the GET-by-id endpoint added by PR #926, nor the post-create
 * {@code version=0} invariant from PR #924. This test is the in-process regression guard
 * for those three concerns specifically, mirroring walkthrough #14 line-for-line.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentCreateDuplicateIdRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> AGENT_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("claude-4-5-haiku");
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    // PR #924 — fresh POST must succeed AND persist with version=0. A regression of
    // setVersion(null) would route save() to em.merge() and raise StaleObjectStateException
    // on the fresh PK, surfacing as a false-positive 409 instead of 201.
    @Test
    void freshPostReturns201AndPersistsWithVersionZero() {
        HttpHeaders auth = adminHeaders("walkthrough14-creator", "wt14-create@test.local");
        String agentId = "walkthrough14-" + UUID.randomUUID();
        Map<String, Object> body = minimalAgentBody(agentId, "Walkthrough #14 — fresh");

        ResponseEntity<Map<String, Object>> create = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), AGENT_TYPE);

        assertEquals(HttpStatus.CREATED, create.getStatusCode(),
                "fresh POST must return 201, not the pre-#924 false-positive 409");
        assertNotNull(create.getBody());
        assertEquals(agentId, create.getBody().get("agentId"));

        Integer version = jdbc.queryForObject(
                "SELECT version FROM agents WHERE id = ?", Integer.class, agentId);
        assertEquals(0, version,
                "post-#924 invariant: fresh INSERT lands with @Version=0 (em.persist path), not 1");
    }

    // PR #926 — GET /api/admin/agents/{id} must exist and return the persisted definition
    // body. The walkthrough's stage 2 uses this as a sanity probe after POST 201.
    @Test
    void getByIdReturnsPersistedAgent() {
        HttpHeaders auth = adminHeaders("walkthrough14-reader", "wt14-read@test.local");
        String agentId = "walkthrough14-" + UUID.randomUUID();
        Map<String, Object> body = minimalAgentBody(agentId, "Walkthrough #14 — readback");

        ResponseEntity<Map<String, Object>> create = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), AGENT_TYPE);
        assertEquals(HttpStatus.CREATED, create.getStatusCode(), "precondition: POST must 201");

        ResponseEntity<Map<String, Object>> read = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.GET, new HttpEntity<>(auth), AGENT_TYPE);

        assertEquals(HttpStatus.OK, read.getStatusCode(),
                "PR #926: GET /api/admin/agents/{id} must return 200, not 404");
        assertNotNull(read.getBody(), "GET /{id} must carry the persisted body");
        assertEquals(agentId, read.getBody().get("agentId"));
        assertEquals("Walkthrough #14 — readback", read.getBody().get("name"));
    }

    // PR #923 — duplicate POST against an already-persisted id must surface as 409
    // (DataIntegrityViolationException handler), and the body must carry a `hint`
    // property naming the offending constraint. Pre-fix the catch-all returned 500
    // with no diagnostic for operators investigating an apparent client retry.
    @Test
    void duplicatePostReturns409WithHint() {
        HttpHeaders auth = adminHeaders("walkthrough14-duplicate", "wt14-dup@test.local");
        String agentId = "walkthrough14-" + UUID.randomUUID();
        Map<String, Object> body = minimalAgentBody(agentId, "Walkthrough #14 — duplicate");

        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), AGENT_TYPE);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), "precondition: first POST must 201");

        ResponseEntity<Map<String, Object>> dup = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), AGENT_TYPE);

        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode(),
                "PR #923: duplicate POST must return 409, not 500 (catch-all) or 201 (overwrite)");
        assertNotNull(dup.getBody(), "409 response must carry a structured body, not be empty");
        Object hint = dup.getBody().get("hint");
        assertNotNull(hint,
                "PR #923 contract: DataIntegrityViolation → 409 body must include a `hint` property "
                        + "naming the offending constraint. Pre-fix the catch-all returned a bare 500 "
                        + "with no diagnostic.");
        assertTrue(hint.toString().length() > 0,
                "the hint must be non-empty so operators can identify the constraint involved");
    }

    private Map<String, Object> minimalAgentBody(String agentId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Created from AgentCreateDuplicateIdRuntimeTest (walkthrough #14 in-process equivalent)");
        body.put("instructions", "Stub agent — never invoked.");
        body.put("model", "claude-4-5-haiku");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        return body;
    }

    private HttpHeaders adminHeaders(String username, String email) {
        return authenticateAs(username, email, "pass-walkthrough14-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
