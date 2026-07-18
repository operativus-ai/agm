package com.operativus.agentmanager.integration.dispatch;

import com.operativus.agentmanager.control.dto.DispatchRunRequest;
import com.operativus.agentmanager.control.dto.OrgRoutingConfigRequest;
import com.operativus.agentmanager.control.dto.OrgRoutingConfigResponse;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the universal-dispatch endpoint. Exercises the full chain:
 * {@code POST /api/runs} → {@code UniversalDispatchController.dispatch} →
 * {@code RoutingResolver.resolveAgentId} → {@code AgentOperations.run} → response.
 *
 * <p>Covers the default_router and rule_classifier resolution strategies plus the
 * "no resolution → 404" path. The LLM classifier is intentionally stubbed in PR-2b;
 * its e2e wiring is a follow-up PR.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.universal-dispatch.enabled=true")
public class UniversalDispatchRuntimeTest extends BaseIntegrationTest {

    private static final String ORG = TenantConstants.DEFAULT_SYSTEM_ORG;

    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetFake() {
        fakeChatModel.reset();
    }

    // --- Strategy 1: default_router ---

    @Test
    void dispatch_defaultRouterConfigured_runsTheRouterAgent() {
        seedFakeModel();

        // PR #885: defaultRouterAgentId must reference a ROUTER-strategy team with at
        // least one active member. Seed both the team and a single member.
        String memberId = "member-" + UUID.randomUUID().toString().substring(0, 8);
        seedAgent(memberId, "Router team specialist member.");
        String routerTeamId = "router-" + UUID.randomUUID().toString().substring(0, 8);
        seedRouterTeam(routerTeamId, List.of(memberId));

        HttpHeaders auth = adminHeaders("disp-default");
        upsertRoutingConfig(auth, new OrgRoutingConfigRequest(routerTeamId, null, false, false, null, null));

        // (1) RouterOrchestrator's structured-output call: picks the member.
        // (2) Member agent's actual chat run.
        fakeChatModel.respondWith("{\"targetAgentId\":\"" + memberId + "\",\"rationale\":\"only member\"}");
        fakeChatModel.respondWith("router answered");

        DispatchRunRequest body = new DispatchRunRequest("Hello, route me.",
                "session-" + UUID.randomUUID(), false, null, null);
        ResponseEntity<RunResponse> resp = rest.exchange(
                url("/api/runs"), HttpMethod.POST,
                new HttpEntity<>(body, auth), RunResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "POST /api/runs with default_router configured must succeed end-to-end");
        assertNotNull(resp.getBody(), "RunResponse body must be populated on success");
    }

    // --- Strategy 3: rule_classifier ---

    @Test
    void dispatch_ruleClassifierMatches_runsTheMatchedAgent() {
        seedFakeModel();
        String weatherId = "weather-" + UUID.randomUUID().toString().substring(0, 8);
        seedAgentWithDescription(weatherId, "Weather forecasts and atmospheric alerts");

        HttpHeaders auth = adminHeaders("disp-rule");
        upsertRoutingConfig(auth, new OrgRoutingConfigRequest(null, null, false, true, null, null));

        fakeChatModel.respondWith("weather agent answered");

        DispatchRunRequest body = new DispatchRunRequest(
                "give me weather updates please", "session-" + UUID.randomUUID(), false, null, null);
        ResponseEntity<RunResponse> resp = rest.exchange(
                url("/api/runs"), HttpMethod.POST,
                new HttpEntity<>(body, auth), RunResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "rule_classifier match on description keyword must route through to the agent");
    }

    // --- No resolution → 404 ---

    @Test
    void dispatch_noResolutionAndNoFallback_returns404() {
        // No routing config row exists for this caller's org
        HttpHeaders auth = adminHeaders("disp-noresolve");

        DispatchRunRequest body = new DispatchRunRequest("any message",
                "session-" + UUID.randomUUID(), false, null, null);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/runs"), HttpMethod.POST,
                new HttpEntity<>(body, auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "POST /api/runs with no resolution must return 404 (config-state problem the operator can fix)");
    }

    @Test
    void dispatch_fallbackOnly_runsFallback() {
        seedFakeModel();
        String fallbackId = "fallback-" + UUID.randomUUID().toString().substring(0, 8);
        seedAgent(fallbackId, "Fallback agent.");

        HttpHeaders auth = adminHeaders("disp-fallback");
        upsertRoutingConfig(auth, new OrgRoutingConfigRequest(null, fallbackId, false, false, null, null));

        fakeChatModel.respondWith("fallback handled it");
        DispatchRunRequest body = new DispatchRunRequest("anything", null, false, null, null);
        ResponseEntity<RunResponse> resp = rest.exchange(
                url("/api/runs"), HttpMethod.POST,
                new HttpEntity<>(body, auth), RunResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "fallback_agent_id must run when no other strategy matches");
    }

    // --- Streaming: resolution failure must fail BEFORE SSE opens ---

    @Test
    void streamDispatch_noResolutionAndNoFallback_returns404Json() {
        HttpHeaders auth = adminHeaders("disp-stream-noresolve");
        auth.set("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);

        DispatchRunRequest body = new DispatchRunRequest("any message",
                "session-" + UUID.randomUUID(), false, null, null);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/runs/stream"), HttpMethod.POST,
                new HttpEntity<>(body, auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "stream endpoint must surface resolution failure as 404 BEFORE opening the SSE channel");
        MediaType ct = resp.getHeaders().getContentType();
        assertNotNull(ct, "error response must declare a Content-Type");
        assertNotEquals(MediaType.TEXT_EVENT_STREAM, ct,
                "SSE channel must not have been opened — Content-Type must be the error JSON, not text/event-stream");
    }

    // --- Validation: missing message ---

    @Test
    void dispatch_blankMessage_returns400() {
        HttpHeaders auth = adminHeaders("disp-blank");
        DispatchRunRequest body = new DispatchRunRequest("   ", null, false, null, null);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/runs"), HttpMethod.POST,
                new HttpEntity<>(body, auth), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "Blank message must be rejected with 400");
    }

    // --- Helpers ---

    private HttpHeaders adminHeaders(String username) {
        HttpHeaders h = authenticateAs(username, username + "@test.local",
                "pass-disp-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
        return h;
    }

    private OrgRoutingConfigResponse upsertRoutingConfig(HttpHeaders auth, OrgRoutingConfigRequest req) {
        ResponseEntity<OrgRoutingConfigResponse> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.PUT,
                new HttpEntity<>(req, auth), OrgRoutingConfigResponse.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "routing-config upsert helper must succeed");
        return resp.getBody();
    }

    private void seedAgent(String agentId, String instructions) {
        jdbc.update("""
                INSERT INTO agents (id, org_id, name, description, instructions, model_id, active,
                                    maintenance_mode, is_team, security_tier, compliance_tier)
                VALUES (?, ?, ?, 'dispatch fixture', ?, 'gpt-4o-mini', true, false, false, 1, 'TIER_1_STANDARD')
                ON CONFLICT (id) DO NOTHING
                """, agentId, ORG, "dispatch test agent " + agentId, instructions);
    }

    private void seedAgentWithDescription(String agentId, String description) {
        jdbc.update("""
                INSERT INTO agents (id, org_id, name, description, instructions, model_id, active,
                                    maintenance_mode, is_team, security_tier, compliance_tier)
                VALUES (?, ?, ?, ?, 'agent instructions', 'gpt-4o-mini', true, false, false, 1, 'TIER_1_STANDARD')
                ON CONFLICT (id) DO NOTHING
                """, agentId, ORG, "dispatch test agent " + agentId, description);
    }

    private void seedRouterTeam(String teamId, List<String> memberIds) {
        String membersJson = "[\"" + String.join("\",\"", memberIds) + "\"]";
        jdbc.update("""
                INSERT INTO agents (id, org_id, name, description, instructions, model_id, active,
                                    maintenance_mode, is_team, team_mode, members, security_tier, compliance_tier)
                VALUES (?, ?, ?, 'router team fixture', 'Pick the best specialist for the request.',
                        'gpt-4o-mini', true, false, true, 'ROUTER', ?::jsonb, 1, 'TIER_1_STANDARD')
                ON CONFLICT (id) DO NOTHING
                """, teamId, ORG, "router team " + teamId, membersJson);
    }

    private void seedFakeModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }
}
