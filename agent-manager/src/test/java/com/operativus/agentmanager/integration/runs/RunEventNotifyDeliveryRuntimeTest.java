package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.control.service.AgentRunEventNotifier;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.SseTestClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves the SSE event streams are driven by Postgres LISTEN/NOTIFY, not just
 *   the fallback poll timer. The poll interval is set to 60s; an event inserted ~after the stream
 *   opens must still be delivered within a 15s client window — which is only possible if the
 *   {@code trg_agent_run_events_notify} trigger + {@link AgentRunEventNotifier} woke the pump early.
 *   If NOTIFY were broken, the pump would sleep the full 60s and the client would time out.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Tag("integration")
@TestPropertySource(properties = {
        // Poll timer effectively disabled for the test window — delivery must come from NOTIFY.
        "agent.run.events.sse.poll-interval-ms=60000",
        "agent.run.events.sse.emitter-timeout-ms=60000",
        "agent.run.events.notify.heartbeat-ms=1000"
})
public class RunEventNotifyDeliveryRuntimeTest extends BaseIntegrationTest {

    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private AgentRunEventNotifier notifier;

    private SseTestClient sse;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        seedModel();
        sse = new SseTestClient("http://localhost:" + port);
    }

    @Test
    void notifyWakesPumpAndDeliversWellBeforeThePollInterval() {
        HttpHeaders auth = registerLoginWithOrg("evt-notify-user", "evt-notify-org");
        String bearer = bearerFrom(auth);
        String agentId = "agent-notify-" + UUID.randomUUID();
        seedAgent(agentId);
        String runId = "run-notify-" + UUID.randomUUID();

        // The notifier must have its LISTEN connection up before we rely on a NOTIFY wake.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(notifier::isHealthy);

        // Insert the run's events shortly after the stream opens (and after its initial empty replay),
        // so delivery can only happen via a NOTIFY-driven wake — not the initial fetch or the 60s timer.
        Thread inserter = new Thread(() -> {
            sleepQuietly(900);
            insertEvent("RUN_START", runId, agentId, "evt-notify-org");
            insertEvent("RUN_COMPLETE", runId, agentId, "evt-notify-org");
        }, "notify-test-inserter");
        inserter.start();

        // Run stream auto-closes on RUN_COMPLETE, so the client returns as soon as the terminal frame
        // arrives. With a 60s poll interval, returning within 15s proves the pump was woken by NOTIFY.
        List<ServerSentEvent<String>> frames = sse.get(
                "/api/v1/runs/" + runId + "/events", bearer, CLIENT_TIMEOUT);

        Set<String> types = eventTypesFrom(frames);
        assertTrue(types.contains("RUN_COMPLETE"),
                "NOTIFY must wake the pump and deliver RUN_COMPLETE within " + CLIENT_TIMEOUT.toSeconds()
                        + "s despite the 60s poll interval — saw: " + types
                        + ". A timeout/empty here means the LISTEN/NOTIFY path is not driving delivery.");
    }

    // ─── helpers ───

    private Set<String> eventTypesFrom(List<ServerSentEvent<String>> frames) {
        Set<String> types = new HashSet<>();
        for (ServerSentEvent<String> f : frames) {
            String data = f.data();
            if (data == null || data.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = json.readValue(data, Map.class);
                Object t = parsed.get("eventType");
                if (t instanceof String s) types.add(s);
            } catch (Exception ignored) {
                // non-JSON frame — skip
            }
        }
        return types;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
