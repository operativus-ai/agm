package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the RunsController tenant + user scoping fix.
 *
 * <p>Pre-fix, {@code GET /api/v1/runs} and {@code GET /api/v1/runs/{id}} had NO
 * tenant filter at all — the controller used {@code findAll…} / {@code findById}
 * directly on the repository, so any authenticated caller could:
 * <ol>
 *   <li>List runs across ALL tenants (no orgId predicate on the SQL).</li>
 *   <li>Fetch any run by id regardless of which tenant owned it.</li>
 *   <li>Filter by another tenant's {@code agentId} / {@code sessionId} and see those runs.</li>
 * </ol>
 *
 * <p>Post-fix, every endpoint resolves orgId from
 * {@link ai.operativus.agentmanager.core.callback.AgentContextHolder} and the
 * by-id endpoint additionally enforces per-user ownership for ROLE_USER callers
 * (matches the SessionController G6 pattern from PR #673).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RunsControllerCrossTenantIdorRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void runsListAndDetailAreTenantScoped_orgACannotReadOrgBRuns() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-runs-A-" + tag;
        String orgB = "org-runs-B-" + tag;

        // Two users, one in each org. registerLoginWithOrg stamps the JWT org_id claim.
        HttpHeaders userAAuth = registerLoginWithOrg("runs-idor-userA-" + tag, orgA);

        // Seed FK deps + an agent + a run for EACH org.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);

        String agentA = "runs-idor-agent-A-" + tag;
        String agentB = "runs-idor-agent-B-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'agent A', 'gpt-4o-mini', true, ?, now(), now()),
                       (?, 'agent B', 'gpt-4o-mini', true, ?, now(), now())
                """, agentA, orgA, agentB, orgB);

        String runA = "runs-idor-run-A-" + tag;
        String runB = "runs-idor-run-B-" + tag;
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, user_id, status, input, output, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 'COMPLETED', 'a-input', 'a-output', now(), now()),
                       (?, ?, ?, NULL, 'COMPLETED', 'b-input', 'b-output', now(), now())
                """, runA, agentA, orgA, runB, agentB, orgB);

        // ── List: org-A caller must only see runs from org-A ───────────────
        ResponseEntity<Map<String, Object>> list = rest.exchange(
                url("/api/v1/runs?size=200"), HttpMethod.GET, new HttpEntity<>(userAAuth), JSON_MAP);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) list.getBody().get("content");
        assertTrue(content.stream().anyMatch(r -> runA.equals(r.get("id"))),
                "org-A caller must see org-A's run in the unfiltered list");
        assertTrue(content.stream().noneMatch(r -> runB.equals(r.get("id"))),
                "org-A caller must NOT see org-B's run via the unfiltered list "
                        + "(pre-fix this surfaced every run across all tenants)");

        // ── By-id: org-A caller asking for org-B's run id → 404 ─────────────
        ResponseEntity<Map<String, Object>> getOrgB = rest.exchange(
                url("/api/v1/runs/" + runB), HttpMethod.GET,
                new HttpEntity<>(userAAuth), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, getOrgB.getStatusCode(),
                "GET /api/v1/runs/{runB} must 404 for org-A caller — pre-fix this returned "
                        + "the org-B run payload (no orgId check on findById)");

        // ── By-id: org-A caller asking for org-A's run id → 200 ─────────────
        ResponseEntity<Map<String, Object>> getOrgA = rest.exchange(
                url("/api/v1/runs/" + runA), HttpMethod.GET,
                new HttpEntity<>(userAAuth), JSON_MAP);
        assertEquals(HttpStatus.OK, getOrgA.getStatusCode(),
                "org-A caller must still be able to read their own run");
        assertEquals(runA, getOrgA.getBody().get("id"));

        // ── Filter spoof: org-A caller filtering by org-B's agentId → empty ─
        ResponseEntity<Map<String, Object>> filterByOrgBAgent = rest.exchange(
                url("/api/v1/runs?agentId=" + agentB + "&size=200"), HttpMethod.GET,
                new HttpEntity<>(userAAuth), JSON_MAP);
        assertEquals(HttpStatus.OK, filterByOrgBAgent.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filterContent =
                (List<Map<String, Object>>) filterByOrgBAgent.getBody().get("content");
        assertTrue(filterContent.isEmpty(),
                "filtering by another tenant's agentId must not leak that tenant's runs "
                        + "(post-fix the org-scoped repository method short-circuits)");
    }
}
