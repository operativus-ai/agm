package ai.operativus.agentmanager.integration.hitl;

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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Black-box runtime coverage of
 *   {@code POST /api/v1/escalations/{escalationId}/resolve} — the typed resume entry
 *   for {@link ai.operativus.agentmanager.core.model.RequiredActionType#SWARM_ESCALATION_APPROVAL}.
 *   Sister surface to {@code /api/v1/approvals/{id}/resolve} (which keys on
 *   {@code approvalId} for tool approvals).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p>Pre-PR-#352 the chat panel resumed escalations via the now-deleted
 * {@code POST /api/agents/{id}/runs/{runId}/continue}. After deletion there was no
 * HTTP entry into {@code AgentService.continueRun} for the escalation variant — every
 * resolve attempt 404'd. This test pins the typed replacement so the gap stays closed.
 *
 * <p>The endpoint dispatches the actual resume on a virtual thread (matches the approval
 * resolve pattern in {@code ApprovalService.resolveApprovalForOrg}), so the HTTP response
 * carries only the {@code runId} echo. Assertions target the response shape, the resolve
 * accounting (status code), and the cross-tenant 404 contract — anything depending on the
 * VT completing successfully would be racy.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class HitlEscalationResolveRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        // agents.model_id has FK fk_agents_model_id → models.id; seed before agent inserts.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // Happy path: APPROVED dispatch returns 202 with the runId echoed in the body —
    // the controller must not block on the virtual-thread resume.
    @Test
    void approveEscalationReturns202WithRunIdAndDecisionEcho() {
        String orgId = "org-esc-approve";
        String username = "esc-approve-runner";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);
        Fixture fx = seedPausedEscalation("esc-approve", orgId);

        Map<String, Object> body = Map.of("decision", "APPROVED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/escalations/" + fx.escalationId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "happy-path resolve must return 202 — 200 would imply we awaited the VT, 4xx a guard regression, 5xx the VT exception leaking through");

        Map<String, Object> payload = resp.getBody();
        assertNotNull(payload, "response body must echo the resolve descriptor");
        assertEquals(fx.escalationId, payload.get("escalationId"));
        assertEquals(fx.runId, payload.get("runId"),
                "runId echo lets the caller invalidate the run-status cache without a separate lookup");
        assertEquals("APPROVED", payload.get("decision"));
    }

    // REJECTED branch must dispatch the same shape — pin separately so a regression that
    // short-circuits one decision arm still fails (common when wiring approve/reject as
    // distinct service paths).
    @Test
    void rejectEscalationReturns202WithRunIdAndDecisionEcho() {
        String orgId = "org-esc-reject";
        String username = "esc-reject-runner";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);
        Fixture fx = seedPausedEscalation("esc-reject", orgId);

        Map<String, Object> body = Map.of("decision", "REJECTED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/escalations/" + fx.escalationId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertEquals("REJECTED", resp.getBody().get("decision"));
        assertEquals(fx.runId, resp.getBody().get("runId"));
    }

    // Cross-tenant: a caller in orgA targeting an escalation in orgB must see the same
    // 404 as a missing escalation. 200 would let the resolve land cross-tenant; 403 would
    // leak tenant-membership probe results.
    @Test
    void resolveCrossTenantEscalationReturns404() {
        String victimOrg = "org-esc-victim";
        String attackerOrg = "org-esc-attacker";
        Fixture fx = seedPausedEscalation("esc-xtenant-victim", victimOrg);

        HttpHeaders attackerAuth = registerLoginWithOrg("esc-xtenant-attacker", attackerOrg);
        Map<String, Object> body = Map.of("decision", "APPROVED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/escalations/" + fx.escalationId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(body, attackerAuth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant escalation resolve must surface as 404 — 200 means the tenant filter is bypassed; 403 would leak tenant-membership signal");

        String victimStatus = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
        assertEquals("PAUSED", victimStatus,
                "cross-tenant resolve must NOT mutate the victim row — anything other than PAUSED means the filter ran AFTER dispatch");
    }

    // Unknown escalationId — same 404 shape as cross-tenant; existence is never leaked.
    @Test
    void resolveUnknownEscalationIdReturns404() {
        String orgId = "org-esc-missing";
        HttpHeaders auth = registerLoginWithOrg("esc-missing-runner", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/escalations/esc-does-not-exist/resolve"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVED"), auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // A decision string outside {APPROVED, REJECTED} — including PENDING, the empty
    // string, or a typo — must be 400 BEFORE any tenant lookup runs (the body-parse
    // guard is cheaper and leaks no tenant signal).
    @Test
    void resolveWithInvalidDecisionReturns400() {
        String orgId = "org-esc-baddecision";
        HttpHeaders auth = registerLoginWithOrg("esc-baddecision-runner", orgId);
        Fixture fx = seedPausedEscalation("esc-baddecision", orgId);

        for (Object decision : new Object[] { "PENDING", "approve", "", null }) {
            Map<String, Object> body = decision == null
                    ? Map.of()
                    : Map.of("decision", decision);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    url("/api/v1/escalations/" + fx.escalationId + "/resolve"),
                    HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                    "decision=" + decision + " must be 400 — controller's body validation must reject any value not in {APPROVED, REJECTED}");
        }

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
        assertEquals("PAUSED", status,
                "no failing decision must dispatch the resume — row stays PAUSED");
    }

    // ─── helpers ───

    private record Fixture(String escalationId, String runId, String agentId, String sessionId) {}

    private Fixture seedPausedEscalation(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String escalationId = "esc-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Escalation Test Agent " + label);

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", orgId, agentId);

        // RequiredAction wire shape — matches RequiredAction.swarmEscalation(...) JSON
        // serialization with @JsonInclude(NON_NULL). Keep this verbatim — it pins the
        // contract that the service-side parser relies on.
        String requiredActionJson = """
                {
                  "type":"SWARM_ESCALATION_APPROVAL",
                  "sourceAgentId":"%s-source",
                  "targetAgentId":"%s-target",
                  "sourceTier":1,
                  "targetTier":2,
                  "escalationId":"%s",
                  "traceId":"trace-%s"
                }
                """.formatted(label, label, escalationId, label);

        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status,
                                        input, required_action, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'PAUSED', 'escalation-input', ?, now(), now(), 0)
                """, runId, agentId, sessionId, label + "-user", orgId, requiredActionJson);

        return new Fixture(escalationId, runId, agentId, sessionId);
    }
}
