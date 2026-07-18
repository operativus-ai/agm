package ai.operativus.agentmanager.integration.audit;

import ai.operativus.agentmanager.control.repository.SystemAuditRepository;
import ai.operativus.agentmanager.core.entity.SystemAuditEntity;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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
 * Domain Responsibility: Pins {@code SystemAuditInterceptor.RESOURCE_TYPE_BY_SEGMENT}
 *   correctly maps URL segments {@code teams} → {@code TEAM} and {@code schedules} →
 *   {@code SCHEDULE} on successful mutations.
 *
 *   <p>Complements:
 *   <ul>
 *     <li>{@code SystemAuditRuntimeTest} (USER mutations — PR #771-era)</li>
 *     <li>{@code SystemAuditRowOnWorkflowMutationsRuntimeTest} (WORKFLOW — PR #781)</li>
 *   </ul>
 *
 *   <p>Scoped down from the original "all 20 resource types" plan to TEAM + SCHEDULE
 *   because those have the simplest minimal-body fixtures (TEAM: 2 fields, SCHEDULE: 7
 *   fields + 1 FK). The other 5 untested types (KNOWLEDGE_BASE, EVALUATION, APPROVAL,
 *   MEMORY, MODEL) need more fixture work; deferred to a follow-up.
 *
 *   <p>The interceptor logic is symmetric across all mapped types, so a future divergence
 *   for any of the 5 deferred types would be a per-resource handler change — that's the
 *   right scope for a follow-up test, not a forklift now.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SystemAuditInterceptorMultiResourceTypeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private SystemAuditRepository systemAuditRepository;

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-interceptor-multi-admin",
                "audit-interceptor-multi-admin@test.local", "pass-aima-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void teamCreatePostWritesOneSystemAuditRowWithResourceTypeTEAM() {
        long baseline = systemAuditRepository.count();

        Map<String, Object> body = Map.of(
                "name", "audit-team-" + UUID.randomUUID(),
                "teamMode", "SEQUENTIAL");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/teams"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "POST /api/v1/teams must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/v1/teams must write exactly 1 system_audits row; got " + delta);
        SystemAuditEntity latest = latest();
        assertEquals("TEAM", latest.getResourceType(),
                "resource_type for /teams segment must be TEAM; got "
                        + latest.getResourceType());
        assertActionContains(latest, "CREATE");
    }

    @Test
    void scheduleCreatePostWritesOneSystemAuditRowWithResourceTypeSCHEDULE() {
        String agentId = seedAgent();
        long baseline = systemAuditRepository.count();

        Map<String, Object> body = Map.of(
                "name", "audit-schedule-" + UUID.randomUUID(),
                "description", "for interceptor pin",
                "cronExpression", "0 0 0 * * *",
                "targetType", "AGENT",
                "targetId", agentId,
                "contextualPrompt", "noop",
                "isActive", true);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/schedules"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "POST /api/v1/schedules must succeed; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(1, delta,
                "POST /api/v1/schedules must write exactly 1 system_audits row; got "
                        + delta);
        SystemAuditEntity latest = latest();
        assertEquals("SCHEDULE", latest.getResourceType(),
                "resource_type for /schedules segment must be SCHEDULE; got "
                        + latest.getResourceType());
        assertActionContains(latest, "CREATE");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String seedAgent() {
        String id = "audit-schedule-target-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'noop', NULL, true, 1, 'TIER_1_STANDARD', false, 0, ?,
                        NOW(), NOW())
                """, id, id, "desc-" + id, TenantConstants.DEFAULT_SYSTEM_ORG);
        return id;
    }

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
