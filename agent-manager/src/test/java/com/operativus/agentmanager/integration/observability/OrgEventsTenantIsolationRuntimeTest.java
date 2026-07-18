package com.operativus.agentmanager.integration.observability;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.SseTestClient;
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
 * Domain Responsibility: Tenant-isolation runtime coverage for the org-wide "all agents" SSE stream
 *   {@code GET /api/v1/observability/events}. Pins that the caller's org is resolved from the JWT and
 *   applied in the repository query, so the firehose surfaces every agent in the caller's tenant but
 *   never another tenant's events.
 *
 *   <p>The stream is long-lived; the emitter timeout is shrunk so the server closes shortly after
 *   replaying the matching rows and {@link SseTestClient} returns the collected frames.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Tag("integration")
@TestPropertySource(properties = {
        "agent.run.events.sse.emitter-timeout-ms=1200",
        "agent.run.events.sse.poll-interval-ms=150"
})
public class OrgEventsTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final Duration SSE_TIMEOUT = Duration.ofSeconds(15);

    private SseTestClient sse;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        seedModel();
        sse = new SseTestClient("http://localhost:" + port);
    }

    @Test
    void orgEventStreamSurfacesAllOwnAgentsButNoOtherTenant() {
        HttpHeaders orgA = registerLoginWithOrg("agm-org-evt-a", "agm-org-evt-A");
        HttpHeaders orgB = registerLoginWithOrg("agm-org-evt-b", "agm-org-evt-B");

        // org A: two different agents; org B: a third agent.
        String agentA1 = "agent-a1-" + UUID.randomUUID();
        String agentA2 = "agent-a2-" + UUID.randomUUID();
        String agentB1 = "agent-b1-" + UUID.randomUUID();
        seedAgent(agentA1);
        seedAgent(agentA2);
        seedAgent(agentB1);
        String runA1 = "run-a1-" + UUID.randomUUID();
        String runA2 = "run-a2-" + UUID.randomUUID();
        String runB1 = "run-b1-" + UUID.randomUUID();
        insertEvent("RUN_START", runA1, agentA1, "agm-org-evt-A");
        insertEvent("RUN_START", runA2, agentA2, "agm-org-evt-A");
        insertEvent("RUN_START", runB1, agentB1, "agm-org-evt-B");

        // org A's firehose sees BOTH its agents' events, none of org B's.
        Set<String> aRunIds = runIdsFrom(sse.get(
                "/api/v1/observability/events", bearerFrom(orgA), SSE_TIMEOUT));
        assertTrue(aRunIds.contains(runA1) && aRunIds.contains(runA2),
                "org A's firehose must surface all of its agents' events — saw: " + aRunIds);
        assertFalse(aRunIds.contains(runB1),
                "org A's firehose must NOT include org B's events — cross-tenant leak. saw: " + aRunIds);

        // org B's firehose sees only its own.
        Set<String> bRunIds = runIdsFrom(sse.get(
                "/api/v1/observability/events", bearerFrom(orgB), SSE_TIMEOUT));
        assertTrue(bRunIds.contains(runB1),
                "org B's firehose must surface its own events — saw: " + bRunIds);
        assertFalse(bRunIds.contains(runA1) || bRunIds.contains(runA2),
                "org B's firehose must NOT include org A's events — cross-tenant leak. saw: " + bRunIds);
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
