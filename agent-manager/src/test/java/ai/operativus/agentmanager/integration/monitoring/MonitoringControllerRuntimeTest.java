package ai.operativus.agentmanager.integration.monitoring;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the 3 endpoints on {@code MonitoringController} that the
 *   dashboard FE consumes:
 *   <ul>
 *     <li>{@code GET /api/monitoring/stats} — aggregate global stats Map</li>
 *     <li>{@code GET /api/monitoring/security/events} — List&lt;ThreatEventDTO&gt;</li>
 *     <li>{@code GET /api/monitoring/security/sandbox} — List&lt;SandboxCapabilityDTO&gt;</li>
 *   </ul>
 *
 *   <p><b>Critical finding pinned by this test</b>: NONE of these endpoints have
 *   {@code @PreAuthorize}. They fall back to {@code SecurityConfig.anyRequest().authenticated()},
 *   so any authenticated principal — including {@code ROLE_USER} — sees the data. The
 *   {@code /security/events} endpoint exposes the threat-event log to non-admins; if that's
 *   not intentional, an {@code @PreAuthorize("hasRole('ADMIN')")} addition is needed at
 *   the class or method level.
 *
 *   <p>This test pins CURRENT behavior. When the gate is added, the {@code roleUserReceives200}
 *   assertions will flip and force a deliberate test update.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li>Anonymous on each endpoint → 401 (Spring's auth filter)</li>
 *     <li>ROLE_USER on each endpoint → 200 + valid envelope (ungated reality)</li>
 *     <li>Empty DB: lists return {@code []} (not null, not 404)</li>
 *     <li>Stats returns a non-null Map (key set may vary by MonitoringService impl)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class MonitoringControllerRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders userAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        userAuth = authenticateAs("monitoring-dashboard-user",
                "monitoring-dashboard-user@test.local", "pass-mdu-1234",
                List.of("ROLE_USER"));
    }

    // ─── Anonymous → 401 ─────────────────────────────────────────────────────

    @Test
    void anonymousGetStatsReturns401() {
        // String.class deserialization sidesteps the RFC-7807 problem+json error body
        // shape mismatch when the response is an unauthorized 401.
        ResponseEntity<String> response = rest.exchange(
                url("/api/monitoring/stats"),
                HttpMethod.GET,
                new HttpEntity<>((Object) null, new HttpHeaders()),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "anonymous /stats must return 401; got " + response.getStatusCode());
    }

    @Test
    void anonymousGetThreatEventsReturns401() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/monitoring/security/events"),
                HttpMethod.GET,
                new HttpEntity<>((Object) null, new HttpHeaders()),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "anonymous /security/events must return 401");
    }

    @Test
    void anonymousGetSandboxCapabilitiesReturns401() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/monitoring/security/sandbox"),
                HttpMethod.GET,
                new HttpEntity<>((Object) null, new HttpHeaders()),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "anonymous /security/sandbox must return 401");
    }

    // ─── ROLE_USER → 403 (gated by class-level @PreAuthorize) ────────────────

    @Test
    void roleUserGetStatsReturns403() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/monitoring/stats"),
                HttpMethod.GET,
                new HttpEntity<>(userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER /stats must be rejected by hasRole('ADMIN') gate; got "
                        + response.getStatusCode());
    }

    @Test
    void roleUserGetThreatEventsReturns403() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/monitoring/security/events"),
                HttpMethod.GET,
                new HttpEntity<>(userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER /security/events must be rejected — threat-event log is "
                        + "ADMIN-only");
    }

    @Test
    void roleUserGetSandboxCapabilitiesReturns403() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/monitoring/security/sandbox"),
                HttpMethod.GET,
                new HttpEntity<>(userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER /security/sandbox must be rejected");
    }

    // ─── ROLE_ADMIN → 200 + valid envelope ───────────────────────────────────

    @Test
    void roleAdminGetThreatEventsReturnsEmptyListOnCleanDb() {
        HttpHeaders adminAuth = authenticateAs("monitoring-dashboard-admin",
                "monitoring-dashboard-admin@test.local", "pass-mda-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/monitoring/security/events"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty(),
                "empty DB must return [] for admin; got " + response.getBody().size());
    }
}
