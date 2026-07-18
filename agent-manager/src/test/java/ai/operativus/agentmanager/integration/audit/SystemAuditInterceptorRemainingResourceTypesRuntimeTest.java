package ai.operativus.agentmanager.integration.audit;

import ai.operativus.agentmanager.control.repository.SystemAuditRepository;
import ai.operativus.agentmanager.core.entity.SystemAuditEntity;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
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
 * Domain Responsibility: Extends per-resource-type coverage of
 *   {@code SystemAuditInterceptor.RESOURCE_TYPE_BY_SEGMENT} beyond the already-covered
 *   USER ({@code SystemAuditRuntimeTest}), WORKFLOW (PR #781), and TEAM + SCHEDULE
 *   (PR #789). This PR adds KNOWLEDGE_BASE, EVALUATION, and MODEL.
 *
 *   <p>Two of the original 5 deferred types (APPROVAL, MEMORY) remain uncovered:
 *   APPROVALS needs a pre-existing approval row to act on, MEMORIES uses a
 *   non-HTTP path in the existing tests. Both need more fixture work than fits this
 *   bounded follow-up; deferred again.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>KNOWLEDGE_BASE</b>: POST {@code /api/v1/knowledge-bases} → 1 row with
 *         {@code resource_type=KNOWLEDGE_BASE}.</li>
 *     <li><b>EVALUATION</b>: POST {@code /api/v1/evaluations/suites} → 1 row with
 *         {@code resource_type=EVALUATION}.</li>
 *     <li><b>MODEL</b>: POST {@code /api/models} → 1 row with
 *         {@code resource_type=MODEL}. Uses ROLE_ADMIN because
 *         {@code ModelService.createModel} has a service-level @PreAuthorize.</li>
 *   </ul>
 *
 *   <p>The interceptor logic is symmetric across all mapped types, so divergence for any
 *   single resource would be a per-resource handler regression — that's the right scope
 *   for a focused test.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class SystemAuditInterceptorRemainingResourceTypesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private SystemAuditRepository systemAuditRepository;

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-interceptor-remaining-admin",
                "audit-interceptor-remaining-admin@test.local", "pass-aira-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void knowledgeBaseCreatePostWritesOneSystemAuditRowWithResourceTypeKNOWLEDGE_BASE() {
        long baseline = systemAuditRepository.count();

        Map<String, Object> body = Map.of(
                "name", "audit-kb-" + UUID.randomUUID(),
                "description", "for interceptor pin");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);

        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST /api/v1/knowledge-bases must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/v1/knowledge-bases must write exactly 1 system_audits row; got "
                        + delta);
        SystemAuditEntity latest = latest();
        assertEquals("KNOWLEDGE_BASE", latest.getResourceType(),
                "resource_type for /knowledge-bases segment must be KNOWLEDGE_BASE; got "
                        + latest.getResourceType());
        assertActionContains(latest, "CREATE");
    }

    @Test
    void evaluationSuiteCreatePostWritesOneSystemAuditRowWithResourceTypeEVALUATION() {
        long baseline = systemAuditRepository.count();

        Map<String, Object> body = Map.of(
                "name", "audit-eval-" + UUID.randomUUID(),
                "description", "for interceptor pin");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/evaluations/suites"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);

        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST /api/v1/evaluations/suites must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/v1/evaluations/suites must write exactly 1 system_audits row; "
                        + "got " + delta);
        SystemAuditEntity latest = latest();
        assertEquals("EVALUATION", latest.getResourceType(),
                "resource_type for /evaluations segment must be EVALUATION; got "
                        + latest.getResourceType());
        assertActionContains(latest, "CREATE");
    }

    @Test
    void modelCreatePostWritesOneSystemAuditRowWithResourceTypeMODEL() {
        long baseline = systemAuditRepository.count();

        // ModelService.createModel is @PreAuthorize-gated to ROLE_ADMIN. Our adminAuth
        // user has ROLE_ADMIN, so this passes.
        Map<String, Object> body = Map.of(
                "name", "audit-model-" + UUID.randomUUID(),
                "provider", "fake",
                "modelName", "fake-gpt",
                "apiKey", "sk-test-fixture-key",
                "modelType", "CHAT");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/models"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);

        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST /api/models must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/models must write exactly 1 system_audits row; got " + delta);
        SystemAuditEntity latest = latest();
        assertEquals("MODEL", latest.getResourceType(),
                "resource_type for /models segment must be MODEL; got "
                        + latest.getResourceType());
        assertActionContains(latest, "CREATE");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private SystemAuditEntity latest() {
        List<SystemAuditEntity> all = systemAuditRepository.findAll();
        assertNotNull(all, "system_audits findAll returned null");
        return all.stream()
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElseThrow(() -> new AssertionError("no system_audits rows present"));
    }

    private static void assertActionContains(SystemAuditEntity row, String expected) {
        String action = row.getAction();
        assertNotNull(action, "system_audits row has null action");
        assertTrue(action.toUpperCase().contains(expected),
                "expected action to contain '" + expected + "'; got '" + action + "'");
    }
}
