package com.operativus.agentmanager.integration.observability;

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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins {@code SloController.getSloStatus} — the dashboard's SLO
 *   status surface ({@code GET /api/v1/observability/slo-status}).
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Anonymous → 401</b> (Spring's auth filter)</li>
 *     <li><b>ROLE_USER → 403</b> ({@code @PreAuthorize("hasRole('ADMIN')")} fires)</li>
 *     <li><b>ROLE_ADMIN → 200 + empty list</b> on a clean DB. The controller's
 *         null-safety guard ({@code if (slos == null) return List.of()}) ensures the
 *         response envelope is always a JSON array, never null — FE doesn't need to
 *         null-check.</li>
 *   </ul>
 *
 *   <p>Companion to {@code AdminEndpointAuthzRuntimeTest} (matrix-level coverage) — adds
 *   the empty-shape FE contract that the matrix doesn't pin.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SloControllerAuthzAndEmptyShapeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {
            };

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void anonymousGetSloStatusReturns401() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/observability/slo-status"),
                HttpMethod.GET,
                new HttpEntity<>((Object) null, new HttpHeaders()),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "anonymous /slo-status must return 401; got " + response.getStatusCode());
    }

    @Test
    void roleUserGetSloStatusReturns403() {
        HttpHeaders userAuth = authenticateAs("slo-user-only",
                "slo-user-only@test.local", "pass-suo-1234",
                List.of("ROLE_USER"));

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/observability/slo-status"),
                HttpMethod.GET,
                new HttpEntity<>(userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER /slo-status must return 403 (hasRole('ADMIN') rejects); got "
                        + response.getStatusCode());
    }

    @Test
    void roleAdminGetSloStatusReturns200WithListBody() {
        HttpHeaders adminAuth = authenticateAs("slo-admin",
                "slo-admin@test.local", "pass-sa-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/v1/observability/slo-status"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "ROLE_ADMIN /slo-status must return 200; got " + response.getStatusCode());
        // The controller's null-safety guard ensures the body is always a non-null List.
        // The list may contain SLO entries if the SloTrackingService has computed any from
        // Micrometer meters that are registered at boot; assert non-null shape (the FE
        // null-safety contract), not exact size.
        assertNotNull(response.getBody(),
                "ROLE_ADMIN response body must be a non-null List (FE expects array, not null)");
    }
}
