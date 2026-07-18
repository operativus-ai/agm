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
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Closes per-resource-type coverage of
 *   {@code SystemAuditInterceptor.RESOURCE_TYPE_BY_SEGMENT} for the final 2 deferred
 *   types: APPROVAL and MEMORY.
 *
 *   <p>Combined with prior PRs, all explicit non-USER types in the interceptor's
 *   mapping are now covered:
 *   <ul>
 *     <li>USER — SystemAuditRuntimeTest</li>
 *     <li>WORKFLOW — PR #781</li>
 *     <li>TEAM, SCHEDULE — PR #789</li>
 *     <li>KNOWLEDGE_BASE, EVALUATION, MODEL — PR #795</li>
 *     <li>APPROVAL, MEMORY — this PR</li>
 *   </ul>
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>APPROVAL</b>: POST {@code /api/v1/approvals/bulk-resolve} with empty {@code ids}
 *         → 1 row with {@code resource_type=APPROVAL}. We use bulk-resolve with empty list
 *         rather than seeding an actual approval row — the interceptor's mapping doesn't
 *         care about handler outcome, only the URL segment.</li>
 *     <li><b>MEMORY</b>: POST {@code /api/memories} with {@code {content}} → 1 row with
 *         {@code resource_type=MEMORY}.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SystemAuditInterceptorApprovalAndMemoryRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private SystemAuditRepository systemAuditRepository;

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-interceptor-final-admin",
                "audit-interceptor-final-admin@test.local", "pass-aifa-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void approvalBulkResolveWritesOneSystemAuditRowWithResourceTypeAPPROVAL() {
        long baseline = systemAuditRepository.count();

        // bulk-resolve with empty ids list — the handler returns successfully (no rows to
        // resolve), the interceptor sees a 2xx response on a path matching the `approvals`
        // segment, and writes one APPROVAL audit row.
        Map<String, Object> body = Map.of(
                "ids", List.of(),
                "decision", "APPROVED");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);

        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST /api/v1/approvals/bulk-resolve must succeed; got "
                        + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/v1/approvals/bulk-resolve must write exactly 1 system_audits row; "
                        + "got " + delta);
        SystemAuditEntity latest = latest();
        assertEquals("APPROVAL", latest.getResourceType(),
                "resource_type for /approvals segment must be APPROVAL; got "
                        + latest.getResourceType());
    }

    @Test
    void memoryAddPostWritesOneSystemAuditRowWithResourceTypeMEMORY() {
        long baseline = systemAuditRepository.count();

        Map<String, Object> body = Map.of("content", "audit-fixture-memory-content");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/memories"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);

        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST /api/memories must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/memories must write exactly 1 system_audits row; got " + delta);
        SystemAuditEntity latest = latest();
        assertEquals("MEMORY", latest.getResourceType(),
                "resource_type for /memories segment must be MEMORY; got "
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
