package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the concurrency contract for sync runs that share a
 *   single {@code sessionId}. {@code AgentService.ensureSessionExists} delegates to
 *   {@code SessionRepository.upsertEnsureExists} — a Postgres
 *   {@code INSERT ... ON CONFLICT (session_id) DO UPDATE} that closes the TOCTOU
 *   window that previously caused N-1 PK violations under N concurrent runs sharing
 *   one new sessionId.
 *
 *   <p>This canary fires N concurrent {@code POST /api/agents/{agentId}/runs} on
 *   the same sessionId via virtual threads. Contract:
 *   <ul>
 *     <li>All N requests succeed (no PK-violation 500s on the session insert path)</li>
 *     <li>Exactly ONE {@code agent_sessions} row exists for the sessionId</li>
 *     <li>Exactly N COMPLETED {@code agent_runs} rows; zero RUNNING orphans</li>
 *   </ul>
 *
 *   <p>Sibling case {@code SyncRunsRuntimeTest.twoSyncRunsWithSameSessionIdAppendToOneSession*}
 *   covers the SEQUENTIAL same-session case.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
// The global test Hikari pool is 3 (application-test.properties), but this canary fires CONCURRENCY
// synchronous runs that each hold a connection for the run's duration — with background schedulers
// also drawing from the pool, 5 > 3 deadlocks and times out. Give this context enough connections to
// actually exercise the concurrency it is testing (CONCURRENCY + headroom for the pollers).
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=12")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ConcurrentSyncRunsRaceRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final int CONCURRENCY = 5;

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetAndSeed() {
        fakeModel.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void nConcurrentSyncRunsOnSameNewSessionId_allSucceed_oneSessionRow_nRunRows() throws Exception {
        HttpHeaders auth = newCaller("race");
        String agentId = createAgent(auth, "Concurrent race probe");
        String sessionId = "sess-race-" + UUID.randomUUID();

        // Pre-script enough canned responses for every concurrent caller. The FakeChatModel
        // script queue is FIFO + thread-safe (CopyOnWriteArrayList / ConcurrentLinkedDeque
        // backing), so concurrent polls each drain one response.
        for (int i = 0; i < CONCURRENCY; i++) {
            fakeModel.respondWith("response-" + i);
        }

        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<ResponseEntity<Map<String, Object>>>[] futures =
                    new CompletableFuture[CONCURRENCY];
            for (int i = 0; i < CONCURRENCY; i++) {
                final int idx = i;
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("message", "request-" + idx);
                    body.put("sessionId", sessionId);
                    ResponseEntity<Map<String, Object>> r = rest.exchange(
                            url("/api/agents/" + agentId + "/runs"),
                            HttpMethod.POST,
                            new HttpEntity<>(body, auth),
                            JSON_MAP);
                    if (r.getStatusCode() == HttpStatus.OK) okCount.incrementAndGet();
                    else failCount.incrementAndGet();
                    return r;
                }, exec);
            }
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
        }

        // Bounded persistence contract (regression-lock for the known TOCTOU race).
        Long sessionRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        Long completedRunRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE session_id = ? AND status = 'COMPLETED'",
                Long.class, sessionId);
        Long runningRunRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE session_id = ? AND status = 'RUNNING'",
                Long.class, sessionId);

        assertAll("concurrent same-sessionId sync runs — TOCTOU race closed by upsert",
                () -> assertEquals(CONCURRENCY, okCount.get(),
                        "every concurrent run must succeed — the upsert closes the TOCTOU "
                                + "window; got okCount=" + okCount.get() + " failCount=" + failCount.get()),
                () -> assertEquals(0, failCount.get(),
                        "no run may fail on the session-insert path; got failCount=" + failCount.get()),
                () -> assertEquals(1L, sessionRows,
                        "exactly one agent_sessions row must exist for the shared sessionId; got "
                                + sessionRows),
                () -> assertEquals((long) CONCURRENCY, completedRunRows,
                        "every concurrent request must produce one COMPLETED agent_runs row; got "
                                + completedRunRows),
                () -> assertEquals(0L, runningRunRows,
                        "no run row may be left stuck in RUNNING; got " + runningRunRows));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "race-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-race-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Concurrent race test agent");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return agentId;
    }
}
