package ai.operativus.agentmanager.integration.retention;

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
 * Domain Responsibility: Black-box runtime coverage for the
 *   {@link ai.operativus.agentmanager.control.controller.DataRetentionController} HTTP surface
 *   ({@code GET /api/admin/retention/policies} and {@code POST /api/admin/retention/purge}).
 *   Service-layer purge behavior is already covered by
 *   {@link ai.operativus.agentmanager.integration.config.ConfigRuntimeTest} (case a) and
 *   {@link ai.operativus.agentmanager.integration.crosscutting.RetentionErasureRuntimeTest};
 *   this class pins only the wire shape, the controller-level @PreAuthorize gate, and the
 *   no-op response shape (empty database → counts all zero).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class DataRetentionRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Integer>> JSON_INT_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void getPoliciesReturnsAllFourRetentionWindows() {
        HttpHeaders auth = adminAuth("ret-policies-admin");

        ResponseEntity<Map<String, Integer>> response = rest.exchange(
                url("/api/admin/retention/policies"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_INT_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/admin/retention/policies must succeed for ROLE_ADMIN");

        Map<String, Integer> body = response.getBody();
        assertNotNull(body, "policies endpoint must return a body");

        // Pin the four documented retention keys (DataRetentionService:160-167).
        // Defaults from application.properties; values are int days, never null.
        assertTrue(body.containsKey("sessions_days"),
                "policies must include sessions_days; got keys " + body.keySet());
        assertTrue(body.containsKey("runs_days"),
                "policies must include runs_days; got keys " + body.keySet());
        assertTrue(body.containsKey("audit_days"),
                "policies must include audit_days; got keys " + body.keySet());
        assertTrue(body.containsKey("alerts_days"),
                "policies must include alerts_days; got keys " + body.keySet());

        for (Map.Entry<String, Integer> e : body.entrySet()) {
            assertNotNull(e.getValue(),
                    "retention day count must never be null; key=" + e.getKey());
            assertTrue(e.getValue() > 0,
                    "retention day count must be positive; key=" + e.getKey() + " value=" + e.getValue());
        }
    }

    @Test
    void purgeOnEmptyDatabaseReturnsZeroCountsForAllTables() {
        HttpHeaders auth = adminAuth("ret-purge-noop-admin");

        // truncateDatabase() ran in @BeforeEach — every retention-target table is empty,
        // so the purge is a structural no-op and every count must be zero.
        ResponseEntity<Map<String, Integer>> response = rest.exchange(
                url("/api/admin/retention/purge"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                JSON_INT_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "POST /api/admin/retention/purge must succeed for ROLE_ADMIN");

        Map<String, Integer> body = response.getBody();
        assertNotNull(body, "purge endpoint must return a body");

        // Pin the documented per-table counts (DataRetentionService:75-133). Each MUST be
        // present in the response shape — a missing key would silently drop a class of
        // purges from the wire contract.
        for (String key : List.of("sessions", "alertEvents", "runs", "agentAudits",
                                   "agentRunEvents", "orchestrationDecisions")) {
            assertTrue(body.containsKey(key),
                    "purge response must include " + key + "; got keys " + body.keySet());
            assertEquals(0, (int) body.get(key),
                    "no rows seeded → purge count for " + key + " must be 0; got " + body.get(key));
        }

        // sseTokens is opt-in (only included when the table exists). Don't pin its presence,
        // but if it's there it must be 0.
        if (body.containsKey("sseTokens")) {
            assertEquals(0, (int) body.get("sseTokens"),
                    "no rows seeded → sseTokens purge count must be 0; got " + body.get("sseTokens"));
        }
    }

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pass-ret-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
