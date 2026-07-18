package com.operativus.agentmanager.integration.audit;

import com.operativus.agentmanager.control.repository.SystemAuditRepository;
import com.operativus.agentmanager.core.entity.SystemAuditEntity;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins that
 *   {@link com.operativus.agentmanager.control.config.interceptor.SystemAuditInterceptor}
 *   writes exactly one {@code system_audits} row for each successful
 *   {@code POST/PUT/PATCH/DELETE} mutation on {@code /api/v1/workflows/**}, with
 *   {@code resource_type=WORKFLOW} extracted from the URL segment per
 *   {@code SystemAuditInterceptor.RESOURCE_TYPE_BY_SEGMENT}.
 *
 *   <p>Complements {@code SystemAuditRuntimeTest} (USER mutations) by covering the
 *   non-agent, non-user mutation surface. The interceptor logic is symmetric across all
 *   mapped resource types, so this WORKFLOW pin guards against:
 *   <ul>
 *     <li>The {@code RESOURCE_TYPE_BY_SEGMENT} mapping breaking for {@code workflows} →
 *         {@code WORKFLOW} (segment rename, mapping removal).</li>
 *     <li>The interceptor accidentally being unregistered for {@code /api/v1/**} paths.</li>
 *     <li>An action-extraction bug (CREATE/UPDATE/DELETE recognition for each verb).</li>
 *   </ul>
 *
 *   <p>Other entity types (SCHEDULE / KNOWLEDGE_BASE / TEAM / EVALUATION / APPROVAL / …)
 *   share the same interceptor code path; this test takes WORKFLOW as the representative
 *   because its DTO is the simplest to fixture (no FK chain to seed). Adding tests for
 *   the others is a follow-up worth doing if the interceptor's per-resource-type behavior
 *   ever diverges.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SystemAuditRowOnWorkflowMutationsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> WORKFLOW_DTO =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private SystemAuditRepository systemAuditRepository;

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("system-audit-workflow-admin",
                "system-audit-workflow-admin@test.local", "pass-sawa-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void workflowCreateWritesExactlyOneSystemAuditRowWithResourceTypeWORKFLOW() {
        long baseline = systemAuditRepository.count();

        Map<String, Object> body = Map.of(
                "id", "wf-audit-" + UUID.randomUUID(),
                "name", "audit-probe",
                "description", "for system-audit interceptor pin");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                WORKFLOW_DTO);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "POST /api/v1/workflows must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/v1/workflows must write exactly 1 system_audits row; got "
                        + delta + " (interceptor may be unregistered for this path or "
                        + "double-counting).");

        SystemAuditEntity latest = latest();
        assertEquals("WORKFLOW", latest.getResourceType(),
                "resource_type for /workflows segment must be WORKFLOW per "
                        + "RESOURCE_TYPE_BY_SEGMENT; got " + latest.getResourceType());
        assertActionContains(latest, "CREATE");
    }

    @Test
    void workflowUpdateWritesExactlyOneSystemAuditRow() {
        Map<String, Object> created = createWorkflow("wf-update-target");
        String workflowId = (String) created.get("id");
        assertNotNull(workflowId, "create response missing id");

        long baseline = systemAuditRepository.count();

        Map<String, Object> updateBody = Map.of(
                "id", workflowId,
                "name", "updated-name",
                "description", "updated-description");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/workflows/" + workflowId),
                HttpMethod.PATCH,
                new HttpEntity<>(updateBody, adminAuth),
                WORKFLOW_DTO);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "PATCH /api/v1/workflows/{id} must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "PATCH /api/v1/workflows/{id} must write exactly 1 system_audits row; got "
                        + delta);

        SystemAuditEntity latest = latest();
        assertEquals("WORKFLOW", latest.getResourceType());
        assertActionContains(latest, "UPDATE");
    }

    @Test
    void workflowDeleteWritesExactlyOneSystemAuditRow() {
        Map<String, Object> created = createWorkflow("wf-delete-target");
        String workflowId = (String) created.get("id");
        assertNotNull(workflowId, "create response missing id");

        long baseline = systemAuditRepository.count();

        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/workflows/" + workflowId),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE /api/v1/workflows/{id} must succeed with 204; got "
                        + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "DELETE /api/v1/workflows/{id} must write exactly 1 system_audits row; got "
                        + delta);

        SystemAuditEntity latest = latest();
        assertEquals("WORKFLOW", latest.getResourceType());
        assertActionContains(latest, "DELETE");
    }

    @Test
    void readOnlyGetOnWorkflowsDoesNotWriteSystemAuditRow() {
        long baseline = systemAuditRepository.count();

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/workflows?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                WORKFLOW_DTO);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/v1/workflows must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(0, delta,
                "GET (read-only) must NOT write a system_audits row; got " + delta
                        + " (interceptor may be erroneously logging reads).");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> createWorkflow(String idPrefix) {
        Map<String, Object> body = Map.of(
                "id", idPrefix + "-" + UUID.randomUUID(),
                "name", "audit-probe",
                "description", "for system-audit interceptor pin");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                WORKFLOW_DTO);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture create must succeed; got " + response.getStatusCode());
        return response.getBody();
    }

    private SystemAuditEntity latest() {
        List<SystemAuditEntity> all = systemAuditRepository.findAll();
        assertTrue(!all.isEmpty(), "no system_audits rows present");
        // Sort by createdAt desc and return the first; for tiny in-test datasets this is fine.
        return all.stream()
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElseThrow();
    }

    private static void assertActionContains(SystemAuditEntity row, String expected) {
        String action = row.getAction();
        assertNotNull(action, "system_audits row has null action");
        assertTrue(action.toUpperCase().contains(expected),
                "expected action to contain '" + expected + "'; got '" + action + "'");
    }
}
