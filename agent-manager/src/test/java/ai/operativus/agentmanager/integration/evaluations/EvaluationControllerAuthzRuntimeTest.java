package ai.operativus.agentmanager.integration.evaluations;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pin {@code EvaluationController} RBAC. Closes a gap missed by
 *   the §28 RBAC sweep that hit {@code AlertingController} (PR #668) — every write
 *   path on {@code /api/v1/evaluations} previously had no {@code @PreAuthorize} gate,
 *   so any authenticated ROLE_USER could create/delete suites, append cases, and
 *   trigger evaluation runs.
 *
 *   Post-fix contract (mirrors {@code SchedulesController} method-level gating):
 *   <ul>
 *     <li>{@code POST /suites}, {@code DELETE /suites/{id}}, {@code POST /suites/{id}/cases},
 *         {@code DELETE /cases/{id}}, {@code POST /suites/{id}/run} require
 *         {@code hasRole('ADMIN')}.</li>
 *     <li>Read paths ({@code GET /suites}, {@code GET /suites/{id}}, ...) remain open
 *         to any authenticated user.</li>
 *     <li>{@code POST /feedback} also remains open — telemetry submission shouldn't
 *         require admin.</li>
 *   </ul>
 *
 *   Each write path is pinned twice: ROLE_USER → 403, ROLE_ADMIN → success path
 *   reachable (validates the gate isn't a no-op).
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class EvaluationControllerAuthzRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void createSuite_roleUser_returns403() {
        HttpHeaders userOnly = userAuth("eval-authz-create-user");

        Map<String, String> body = Map.of("name", "blocked", "description", "RBAC probe");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.POST,
                new HttpEntity<>(body, userOnly), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "POST /suites must require ROLE_ADMIN; ROLE_USER must hit @PreAuthorize");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_suites WHERE name = ?", Long.class, "blocked");
        assertEquals(0L, count == null ? 0L : count,
                "rejected POST must not persist a suite row");
    }

    @Test
    void createSuite_roleAdmin_returns200() {
        HttpHeaders adminAuth = adminAuth("eval-authz-create-admin");

        Map<String, String> body = Map.of("name", "admin-allowed-" + UUID.randomUUID(),
                "description", "RBAC happy path");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.POST,
                new HttpEntity<>(body, adminAuth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "ROLE_ADMIN must reach the controller; got " + resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().get("id"), "successful create returns a suite id");
    }

    @Test
    void deleteSuite_roleUser_returns403_andRowSurvives() {
        // Seed a suite directly so the test does not depend on the create gate.
        String suiteId = "suite-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_suites (id, name, description, created_by, created_at, updated_at)
                VALUES (?, 'delete-target', 'RBAC delete probe', 'system', now(), now())
                """, suiteId);

        HttpHeaders userOnly = userAuth("eval-authz-delete-user");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId), HttpMethod.DELETE,
                new HttpEntity<>(userOnly), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "DELETE /suites/{id} must require ROLE_ADMIN");

        Long exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_suites WHERE id = ?", Long.class, suiteId);
        assertEquals(1L, exists == null ? 0L : exists,
                "rejected DELETE must not have removed the row");
    }

    @Test
    void addCaseToSuite_roleUser_returns403() {
        String suiteId = "suite-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_suites (id, name, description, created_by, created_at, updated_at)
                VALUES (?, 'case-host', 'RBAC case probe', 'system', now(), now())
                """, suiteId);

        HttpHeaders userOnly = userAuth("eval-authz-case-user");
        Map<String, Object> body = Map.of(
                "name", "blocked-case",
                "input", "hello");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/cases"), HttpMethod.POST,
                new HttpEntity<>(body, userOnly), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "POST /suites/{id}/cases must require ROLE_ADMIN");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_cases WHERE suite_id = ?",
                Long.class, suiteId);
        assertEquals(0L, count == null ? 0L : count,
                "rejected POST must not persist a case row");
    }

    @Test
    void deleteCase_roleUser_returns403() {
        String suiteId = "suite-" + UUID.randomUUID();
        String caseId = "case-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_suites (id, name, description, created_by, created_at, updated_at)
                VALUES (?, 'case-del-host', 'host', 'system', now(), now())
                """, suiteId);
        jdbc.update("""
                INSERT INTO evaluation_cases (id, suite_id, name, input, created_at, updated_at)
                VALUES (?, ?, 'delete-target', 'probe', now(), now())
                """, caseId, suiteId);

        HttpHeaders userOnly = userAuth("eval-authz-delcase-user");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/cases/" + caseId), HttpMethod.DELETE,
                new HttpEntity<>(userOnly), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "DELETE /cases/{id} must require ROLE_ADMIN");

        Long exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_cases WHERE id = ?", Long.class, caseId);
        assertEquals(1L, exists == null ? 0L : exists,
                "rejected DELETE must not have removed the case row");
    }

    @Test
    void runSuite_roleUser_returns403() {
        String suiteId = "suite-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_suites (id, name, description, created_by, created_at, updated_at)
                VALUES (?, 'run-target', 'RBAC run probe', 'system', now(), now())
                """, suiteId);
        String agentId = "agent-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'eval-authz-agent', 'gpt-4o-mini', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId);

        HttpHeaders userOnly = userAuth("eval-authz-run-user");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/run?agentId=" + agentId),
                HttpMethod.POST, new HttpEntity<>(userOnly), String.class);

        assertAll("ROLE_USER must not trigger a run",
                () -> assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                        "POST /suites/{id}/run must require ROLE_ADMIN"),
                () -> {
                    Long jobs = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM background_jobs WHERE agent_id = ?",
                            Long.class, agentId);
                    assertEquals(0L, jobs == null ? 0L : jobs,
                            "rejected run must not have enqueued an EVALUATION_RUN job");
                });
    }

    @Test
    void readPaths_roleUser_remainOpen() {
        // Confirm the gate is method-level (not class-level) — reads still 200 for ROLE_USER.
        HttpHeaders userOnly = userAuth("eval-authz-read-user");

        ResponseEntity<String> listSuites = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.GET,
                new HttpEntity<>(userOnly), String.class);
        ResponseEntity<String> metrics = rest.exchange(
                url("/api/v1/evaluations/metrics"), HttpMethod.GET,
                new HttpEntity<>(userOnly), String.class);

        assertAll("read paths remain unrestricted post-RBAC",
                () -> assertEquals(HttpStatus.OK, listSuites.getStatusCode(),
                        "GET /suites must remain open to ROLE_USER"),
                () -> assertNotEquals(HttpStatus.FORBIDDEN, listSuites.getStatusCode()),
                () -> assertEquals(HttpStatus.OK, metrics.getStatusCode(),
                        "GET /metrics must remain open to ROLE_USER"));
    }

    // ─── helpers ───

    private HttpHeaders userAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-eval-authz-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-eval-authz-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
