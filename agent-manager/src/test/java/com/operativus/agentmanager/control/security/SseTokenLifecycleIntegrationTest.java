package com.operativus.agentmanager.control.security;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.SseTokenResponse;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-T005 end-to-end: a real authenticated user POSTs to /sse-token, receives a UUID,
 * then a second EventSource-style GET with that same token (to a non-matching run) → 401
 * mismatched_run; and a second GET with the same token to the right run after consume →
 * 401 invalid (single-use enforced).
 *
 * Default Caffeine store is in play here — the test exercises the full HTTP/Security chain
 * including SseTokenAuthFilter ordering ahead of JwtAuthenticationFilter.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
@TestPropertySource(properties = {
        // Short emitter timeout so the live-tail GET in the wrong-run/right-run flow returns
        // fast — the RestTemplate isn't streaming-aware and would otherwise block until the
        // production 30-minute default. Two seconds is more than enough for the filter +
        // SecurityContext + first poll cycle to complete and the pump to time out.
        "agent.run.events.sse.emitter-timeout-ms=2000",
        "agent.run.events.sse.poll-interval-ms=200"
})
class SseTokenLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired private RunRepository runRepository;
    @Autowired private AgentRepository agentRepository;

    @Test
    void issueToken_thenAttemptReuseAndWrongRun_BehaviorMatchesContract() {
        // Seed a run owned by the authenticating user's org. RunRepository persists with
        // JPA auditing; setting the fields the controller reads is sufficient.
        String userSuffix = Long.toHexString(System.nanoTime());
        HttpHeaders auth = authenticateAs(
                "sse-tok-" + userSuffix,
                "sse-tok-" + userSuffix + "@test.local",
                "pass-sse-1234",
                List.of("ROLE_USER"));

        String runIdA = "run-sse-" + userSuffix + "-A";
        String runIdB = "run-sse-" + userSuffix + "-B";
        String agentId = "agent-sse-" + userSuffix;
        seedAgent(agentId);
        seedRun(runIdA, agentId);
        seedRun(runIdB, agentId);

        // 1. POST /sse-token → 200 with token
        ResponseEntity<SseTokenResponse> issued = rest.exchange(
                url("/api/v1/runs/" + runIdA + "/sse-token"),
                HttpMethod.POST, new HttpEntity<>(auth), SseTokenResponse.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody()).isNotNull();
        String token = issued.getBody().token();
        assertThat(token).isNotBlank();

        // 2. Use token on the WRONG run → 401 mismatched_run; token NOT consumed
        ResponseEntity<String> wrongRun = rest.exchange(
                url("/api/v1/runs/" + runIdB + "/events?token=" + token),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(wrongRun.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongRun.getBody()).contains("sse_token_run_mismatch");

        // 3. Use the same token on the RIGHT run again — first time, still works because
        //    step 2 did NOT consume it (mismatched-run preservation).
        //    We don't subscribe; just send a HEAD-like request and stop. SseEmitter will
        //    open the stream; since RestTemplate isn't streaming-friendly we expect a 200
        //    with content-type text/event-stream. We accept either OK or any 2xx.
        ResponseEntity<String> firstUse = rest.exchange(
                url("/api/v1/runs/" + runIdA + "/events?token=" + token),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(firstUse.getStatusCode().is2xxSuccessful()).isTrue();

        // 4. Use the now-consumed token AGAIN → 401 invalid
        ResponseEntity<String> reused = rest.exchange(
                url("/api/v1/runs/" + runIdA + "/events?token=" + token),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody()).contains("sse_token_invalid");
    }

    @Test
    void issueToken_runDoesNotExist_Returns404() {
        String userSuffix = Long.toHexString(System.nanoTime());
        HttpHeaders auth = authenticateAs(
                "sse-tok2-" + userSuffix,
                "sse-tok2-" + userSuffix + "@test.local",
                "pass-sse-1234",
                List.of("ROLE_USER"));

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/runs/" + UUID.randomUUID() + "/sse-token"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getEvents_NoTokenNoBearer_FallsThroughTo401FromJwtFilter() {
        // No ?token= and no Authorization header — SseTokenAuthFilter passes through, the
        // JWT filter then (correctly) rejects. Confirms the SSE filter doesn't intercept
        // unrelated requests.
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/runs/run-anything/events"),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void seedAgent(String agentId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setName(agentId);
        agentRepository.saveAndFlush(agent);
    }

    private void seedRun(String runId, String agentId) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setAgentId(agentId);
        // sessionId left null — fk_agent_runs_session only fires when the column is set,
        // and the SseTokenController doesn't read it.
        run.setStatus(RunStatus.RUNNING);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        // No orgId on the seeded run — controller short-circuits its org check when run.orgId is null,
        // so the authenticated user can issue tokens regardless of their own org claim.
        runRepository.saveAndFlush(run);
    }
}
