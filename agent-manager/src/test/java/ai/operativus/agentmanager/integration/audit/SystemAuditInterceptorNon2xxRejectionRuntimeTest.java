package ai.operativus.agentmanager.integration.audit;

import ai.operativus.agentmanager.control.repository.SystemAuditRepository;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pins {@code SystemAuditInterceptor}'s 2xx-only contract — it
 *   writes a {@code system_audits} row only when the response status is in [200, 300).
 *   Non-2xx mutations (400 validation, 404 not-found, 5xx errors) must NOT write a row.
 *
 *   <p>Why this matters: if the interceptor logged a row on every mutation regardless
 *   of outcome, an attacker probing the admin surface with malformed requests would fill
 *   the audit table with false "user mutated X" rows, drowning out real signals during
 *   forensic review.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>GET on a mutation-able path</b> writes NO row (interceptor checks the verb).</li>
 *     <li><b>DELETE on a non-existent resource</b> → 404 writes NO row.</li>
 *     <li><b>POST with malformed body</b> → 400 writes NO row.</li>
 *   </ul>
 *
 *   <p>Complements {@code SystemAuditRuntimeTest} and the multi-resource interceptor pin.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SystemAuditInterceptorNon2xxRejectionRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private SystemAuditRepository systemAuditRepository;

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-non2xx-admin",
                "audit-non2xx-admin@test.local", "pass-ana-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void getOnMutationablePathWritesNoSystemAuditRow() {
        long baseline = systemAuditRepository.count();

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/teams?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/v1/teams must return 200; got " + response.getStatusCode());

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(0, delta,
                "GET (read-only) must NOT write a system_audits row; got " + delta
                        + " (interceptor may be erroneously logging reads).");
    }

    @Test
    void postWithMalformedBodyWritesNoSystemAuditRow() {
        long baseline = systemAuditRepository.count();

        // Empty body — fails @Valid on the controller, returns 400 before any business
        // logic runs. The interceptor must NOT write a row for this 400.
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/teams"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), adminAuth),
                JSON_MAP);

        HttpStatusCode status = response.getStatusCode();
        assertNotEquals(HttpStatus.OK, status,
                "POST with empty body must NOT return 200 OK; got " + status);
        assertNotEquals(HttpStatus.CREATED, status,
                "POST with empty body must NOT return 201 CREATED; got " + status);

        long delta = systemAuditRepository.count() - baseline;
        assertEquals(0, delta,
                "400 validation failure must NOT write a system_audits row; got " + delta
                        + ". Response status was " + status + ".");
    }
}
