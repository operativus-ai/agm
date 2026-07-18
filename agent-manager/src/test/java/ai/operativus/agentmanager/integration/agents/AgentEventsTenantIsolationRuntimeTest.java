package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.SseTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the per-agent SSE stream
 *   {@code GET /api/v1/agents/{agentId}/events}. Pins that the caller's org is resolved from the
 *   JWT (not a request param) and applied in the repository query, so org B can never tail org A's
 *   agent events — even when both orgs have rows for the SAME agent_id in {@code agent_run_events}.
 *   This is the same IDOR class fixed elsewhere this session; the events stream must not reopen it.
 *
 *   <p>The per-agent stream is long-lived (it does NOT close on a run's terminal event). To keep
 *   the test bounded we shrink the emitter timeout so the server closes the connection shortly
 *   after replaying the matching rows; {@link SseTestClient} then returns the collected frames.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Tag("integration")
@TestPropertySource(properties = {
        "agent.run.events.sse.emitter-timeout-ms=1200",
        "agent.run.events.sse.poll-interval-ms=150"
})
public class AgentEventsTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final Duration SSE_TIMEOUT = Duration.ofSeconds(15);

    private SseTestClient sse;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        seedModel();
        sse = new SseTestClient("http://localhost:" + port);
    }

    @Test
    void agentEventStreamIsScopedToCallerOrg() {
        HttpHeaders orgA = registerLoginWithOrg("agm-evt-iso-a", "agm-evt-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agm-evt-iso-b", "agm-evt-org-B");

        // Same agent_id, events for BOTH orgs — the strongest cross-tenant probe.
        String agentId = "agent-evt-iso-" + UUID.randomUUID();
        seedAgent(agentId);
        String runA = "run-orgA-" + UUID.randomUUID();
        String runB = "run-orgB-" + UUID.randomUUID();
        insertEvent("RUN_START", runA, agentId, "agm-evt-org-A");
        insertEvent("RUN_COMPLETE", runA, agentId, "agm-evt-org-A");
        insertEvent("RUN_START", runB, agentId, "agm-evt-org-B");
        insertEvent("RUN_COMPLETE", runB, agentId, "agm-evt-org-B");

        // org A sees ONLY its own run's events
        Set<String> aRunIds = runIdsFrom(sse.get(
                "/api/v1/agents/" + agentId + "/events", bearerFrom(orgA), SSE_TIMEOUT));
        assertTrue(aRunIds.contains(runA),
                "org A must see its own agent events — saw runIds: " + aRunIds);
        assertFalse(aRunIds.contains(runB),
                "org A must NOT see org B's events for the same agent_id — IDOR leak. runIds: " + aRunIds);

        // org B sees ONLY its own run's events
        Set<String> bRunIds = runIdsFrom(sse.get(
                "/api/v1/agents/" + agentId + "/events", bearerFrom(orgB), SSE_TIMEOUT));
        assertTrue(bRunIds.contains(runB),
                "org B must see its own agent events — saw runIds: " + bRunIds);
        assertFalse(bRunIds.contains(runA),
                "org B must NOT see org A's events for the same agent_id — IDOR leak. runIds: " + bRunIds);
    }

    @Test
    void startFromLatestSentinelSkipsExistingHistory() {
        HttpHeaders orgA = registerLoginWithOrg("agm-evt-tail-a", "agm-evt-tail-org-A");

        String agentId = "agent-evt-tail-" + UUID.randomUUID();
        seedAgent(agentId);
        String oldRun = "run-old-" + UUID.randomUUID();
        insertEvent("RUN_START", oldRun, agentId, "agm-evt-tail-org-A");
        insertEvent("RUN_COMPLETE", oldRun, agentId, "agm-evt-tail-org-A");

        // sinceId=-1 resolves (via findMaxIdByAgentIdAndOrgId) to the latest existing id and tails
        // from there, so none of the pre-existing history replays.
        Set<String> runIds = runIdsFrom(sse.get(
                "/api/v1/agents/" + agentId + "/events?sinceId=-1", bearerFrom(orgA), SSE_TIMEOUT));
        assertFalse(runIds.contains(oldRun),
                "start-from-latest must NOT replay history — saw runIds: " + runIds);
    }

    // ─── helpers ───

    private Set<String> runIdsFrom(List<ServerSentEvent<String>> frames) {
        Set<String> runIds = new HashSet<>();
        for (ServerSentEvent<String> f : frames) {
            String data = f.data();
            if (data == null || data.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = json.readValue(data, Map.class);
                Object r = parsed.get("runId");
                if (r instanceof String s) runIds.add(s);
            } catch (Exception ignored) {
                // non-JSON frame (comment/keepalive) — skip
            }
        }
        return runIds;
    }

    private String bearerFrom(HttpHeaders auth) {
        String header = auth.getFirst("Authorization");
        return header == null ? "" : header.substring("Bearer ".length());
    }

    private void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('fake-model', 'fake-model', 'fake', 'fake-model', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void seedAgent(String agentId) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'fake-model', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, agentId);
    }

    private void insertEvent(String eventType, String runId, String agentId, String orgId) {
        jdbc.update("""
                INSERT INTO agent_run_events (event_type, run_id, agent_id, session_id, org_id, payload, event_ts)
                VALUES (?, ?, ?, ?, ?, '{}'::jsonb, now())
                """, eventType, runId, agentId, "sess-" + orgId, orgId);
    }
}
