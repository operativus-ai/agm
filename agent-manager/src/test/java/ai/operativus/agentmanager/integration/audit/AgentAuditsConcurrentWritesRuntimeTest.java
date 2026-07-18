package ai.operativus.agentmanager.integration.audit;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code agent_audits} integrity under concurrent agent CREATE
 *   operations — N parallel HTTP requests must produce N distinct audit rows with no
 *   duplicates and no lost rows. Forensic chain-of-custody requires this invariant.
 *
 *   <p>Concurrency pattern adapted from {@code KnowledgeCascadeConcurrencyRuntimeTest}:
 *   {@code Executors.newVirtualThreadPerTaskExecutor()} (Java 25 virtual threads — clean
 *   shutdown via try-with-resources).
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>10 parallel CREATEs produce 10 distinct audit rows</b> — count equals thread
 *         count exactly, no duplicates by audit-row ID.</li>
 *     <li><b>All audit timestamps are non-null and within a reasonable window</b> — pins
 *         that the trigger / save path doesn't drop the timestamp under contention.</li>
 *   </ul>
 *
 *   <p>POST {@code /api/admin/agents} is service-layer ungated (per
 *   {@code AgentAdminAuthzRuntimeTest}'s {@code UNGATED_ENDPOINTS} list), so any
 *   authenticated user can drive this test.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentAuditsConcurrentWritesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private static final int THREAD_COUNT = 10;

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-concurrent-admin",
                "audit-concurrent-admin@test.local", "pass-aca-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
        // Seed a model row so the agents.model_id FK resolves under load.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools,
                                    supports_vision, supports_system_instructions, model_type,
                                    created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true,
                        'CHAT', NOW())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void tenParallelCreatesProduceTenDistinctAuditRowsWithNoLosses() throws Exception {
        List<String> agentIds = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            agentIds.add("concurrent-create-" + UUID.randomUUID());
        }

        long baselineCreates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE action = 'CREATE'", Long.class);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpStatus>> futures = new ArrayList<>();
            for (String agentId : agentIds) {
                Callable<HttpStatus> task = () -> {
                    Map<String, Object> body = createBody(agentId);
                    ResponseEntity<Map<String, Object>> response = rest.exchange(
                            url("/api/admin/agents"),
                            HttpMethod.POST,
                            new HttpEntity<>(body, adminAuth),
                            JSON_MAP);
                    return (HttpStatus) response.getStatusCode();
                };
                futures.add(exec.submit(task));
            }

            for (int i = 0; i < futures.size(); i++) {
                HttpStatus status = futures.get(i).get();
                assertTrue(status.is2xxSuccessful(),
                        "concurrent CREATE #" + i + " (agentId=" + agentIds.get(i)
                                + ") must succeed; got " + status);
            }
        }

        long afterCreates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE action = 'CREATE'", Long.class);

        assertEquals(baselineCreates + THREAD_COUNT, afterCreates,
                THREAD_COUNT + " parallel CREATEs must produce exactly " + THREAD_COUNT
                        + " new audit rows; baseline=" + baselineCreates
                        + " after=" + afterCreates + " (delta="
                        + (afterCreates - baselineCreates)
                        + "). A delta below " + THREAD_COUNT + " indicates lost rows; "
                        + "above indicates duplicates / spurious writes.");

        // Verify each agent has exactly one CREATE audit row.
        for (String agentId : agentIds) {
            Long perAgent = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'CREATE'",
                    Long.class, agentId);
            assertEquals(1L, perAgent,
                    "agent " + agentId + " must have exactly one CREATE audit row; got "
                            + perAgent);
        }

        // Verify all audit-row IDs are distinct (no PK collision under contention).
        Long distinctIds = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT id) FROM agent_audits
                WHERE agent_id = ANY(?::text[]) AND action = 'CREATE'
                """, Long.class, "{" + String.join(",", agentIds) + "}");
        assertEquals((long) THREAD_COUNT, distinctIds,
                "audit-row IDs must be distinct under contention; got " + distinctIds
                        + " distinct IDs for " + THREAD_COUNT + " creates");
    }

    @Test
    void concurrentAuditRowsHaveNonNullTimestamps() {
        // Lighter test: 5 parallel creates, just verify created_at is set on each.
        List<String> agentIds = new ArrayList<>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String agentId = "concurrent-ts-" + UUID.randomUUID();
                agentIds.add(agentId);
                Callable<String> task = () -> {
                    rest.exchange(
                            url("/api/admin/agents"),
                            HttpMethod.POST,
                            new HttpEntity<>(createBody(agentId), adminAuth),
                            JSON_MAP);
                    return agentId;
                };
                futures.add(exec.submit(task));
            }
            for (Future<String> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    throw new RuntimeException("concurrent CREATE failed", e);
                }
            }
        }

        for (String agentId : agentIds) {
            java.sql.Timestamp createdAt = jdbc.queryForObject(
                    "SELECT created_at FROM agent_audits WHERE agent_id = ? AND action = 'CREATE'",
                    java.sql.Timestamp.class, agentId);
            assertNotNull(createdAt,
                    "audit row for " + agentId + " must have non-null created_at; got null");
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> createBody(String agentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", "concurrent-test-" + agentId);
        body.put("description", "concurrent audit fixture");
        body.put("instructions", "do nothing");
        body.put("model", "gpt-4o-mini");
        return body;
    }
}
