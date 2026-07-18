package com.operativus.agentmanager.integration.extensions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Extensions surface —
 *   {@link com.operativus.agentmanager.control.controller.ExtensionController}
 *   ({@code /api/v1/extensions} CRUD), the per-agent
 *   {@link com.operativus.agentmanager.compute.advisor.ExtensionHookAdvisor}
 *   wiring inside the ChatClient advisor chain, and the WEBHOOK dispatch path
 *   that {@code ExtensionHookAdvisor.dispatchHook} routes through
 *   {@code WebClient.post().uri(extension.getUrl())...}. Pins:
 *     - the registration round-trip (DB row + DTO),
 *     - that an agent's {@code postHooks} list triggers a real outbound webhook
 *       call after the LLM completes,
 *     - the fail-safe contract (webhook 5xx does NOT fail the run),
 *     - that an inactive extension is short-circuited inside dispatch,
 *     - the read-only contract (webhook body cannot mutate prompt/response),
 *     - and the RBAC gap (no {@code @PreAuthorize} on the controller).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §16 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T039 (6 cases).
 *
 * Webhook boundary: {@code ExtensionHookAdvisor} resolves an extension by id from
 *   {@code ExtensionRegistrationRepository} and POSTs the hook payload to
 *   {@code extension.getUrl()} via the production {@code extensionWebClient}.
 *   Pointing the URL at a per-class WireMock instance on a dynamic localhost port
 *   exercises the real WebClient path end-to-end without external network. No
 *   production refactor — same seam pattern decisions.md "T020 URL-fetch seam"
 *   established for Jsoup-driven URL ingest.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link com.operativus.agentmanager.compute.advisor.ExtensionHookAdvisor#dispatchHook}
 *     wraps the WebClient call in try/catch and only logs at WARN — webhook errors
 *     never propagate up to the run. Case (2) pins this fail-safe contract.
 *   - The advisor reads {@code extension.getActive()} after repo lookup
 *     ({@code ExtensionHookAdvisor.java:184}); when {@code false} it logs and
 *     returns without dispatching. Case (3) pins the no-call invariant.
 *   - The advisor extracts user/output text via {@code extractUserText} /
 *     {@code extractOutputText} and passes it as a webhook payload, but does NOT
 *     consume the webhook response — the body is read into a {@code Mono<String>}
 *     and discarded. Case (4) pins this as a GAP against the spec's
 *     "optionally mutate prompt context" clause. The "read" half of the spec
 *     IS exercised by asserting the inbound POST body carries the original prompt.
 *   - Spec case (5) — SPI workflow-step extension lifecycle — is covered by
 *     {@code WorkflowsRuntimeTest} case 9 (SPI dispatch via
 *     {@code WorkflowStepExecutorExtension}). T039 keeps a {@code @Disabled}
 *     placeholder so the case-count aligns with the spec table.
 *   - {@link com.operativus.agentmanager.control.controller.ExtensionController}
 *     has no {@code @PreAuthorize} annotations on any endpoint. Same gap flavor
 *     as T021 MemoryController, T024 SettingsController, T035 WorkflowsController,
 *     T036 ApprovalsController, T037 SchedulesController. Case (6) pins the
 *     as-shipped behaviour: a plain {@code ROLE_USER} caller can POST + DELETE
 *     extension registrations. The canonical admin-only annotation in this repo
 *     (e.g. {@code ComplianceController}) is
 *     {@code @PreAuthorize("hasRole('ADMIN')")}; flip this case once it's added.
 *   - Agent post-hook wiring: {@link com.operativus.agentmanager.core.entity.AgentEntity}
 *     persists {@code preHooks} / {@code postHooks} as {@code jsonb List<String>}
 *     columns. The {@code POST /api/admin/agents} create DTO accepts both fields;
 *     {@code AgentClientFactory.buildChatClient} (line 452-458) instantiates
 *     {@code ExtensionHookAdvisor} only when at least one list is non-empty.
 */
// Webhook fixtures register URLs at the local WireMock server (loopback), which the write-time
// SSRF guard rejects by default. Permit loopback for this suite only; ExtensionControllerSsrfTest
// keeps the secure default and still verifies the guard rejects private/loopback URLs.
@org.springframework.test.context.TestPropertySource(properties = "agm.extensions.ssrf.allow-loopback=true")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ExtensionsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    private static WireMockServer wiremock;

    @Autowired private FakeChatModel fakeModel;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wiremock != null) wiremock.stop();
    }

    @BeforeEach
    void resetFixtures() {
        wiremock.resetAll();
        fakeModel.reset();
        seedModel("gpt-4o-mini");
    }

    // ─── §16 Case 1 — registration → advisor invokes during run ───

    /**
     * Spec §16.1. Register a WEBHOOK extension via the admin API, attach it to a
     *   newly-created agent's {@code postHooks} list, then issue a sync run. The
     *   agent's run should complete normally AND WireMock must observe exactly one
     *   POST to the registered webhook URL. Pins: the registration round-trip
     *   ({@code POST /api/v1/extensions} returns 200 + DB row + GET listing
     *   includes the new entry), the per-agent advisor wiring (preHooks/postHooks
     *   on {@code AgentEntity}), and the post-hook dispatch contract (one POST
     *   per registered post-hook, with {@code X-Hook-Phase: POST} and the
     *   assistant's reply text in the JSON {@code payload} field).
     */
    @Test
    void registerWebhookExtensionAsPostHook_advisorPostsToWebhookAfterRun() {
        HttpHeaders auth = authenticatedHeaders("ext-postHook-runner");

        String hookId = "ext-post-" + UUID.randomUUID();
        String hookPath = "/hooks/post-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", hookId);
        regBody.put("name", "Post-hook test");
        regBody.put("type", "WEBHOOK");
        regBody.put("url", "http://localhost:" + wiremock.port() + hookPath);
        regBody.put("description", "T039 case 1");
        regBody.put("active", true);
        ResponseEntity<Map<String, Object>> registered = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(regBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, registered.getStatusCode(),
                "ExtensionController.registerExtension returns 200 (no @ResponseStatus(CREATED))");
        assertEquals(hookId, registered.getBody().get("id"),
                "registration response must echo the supplied id, not generate a new UUID");

        Long dbCount = jdbc.queryForObject(
                "SELECT count(*) FROM extensions WHERE id = ?", Long.class, hookId);
        assertEquals(1L, dbCount, "extensions row must persist on registration");

        ResponseEntity<List<Map<String, Object>>> listing = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, listing.getStatusCode());
        assertTrue(listing.getBody().stream().anyMatch(e -> hookId.equals(e.get("id"))),
                "GET /api/v1/extensions must surface the newly-registered WEBHOOK extension");

        String agentId = createAgentWithPostHook(auth, "T039 PostHook Agent", hookId);

        String cannedReply = "T039 case 1 assistant response";
        fakeModel.respondWith(cannedReply);

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "T039 case 1 user prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, run.getStatusCode(), "happy-path sync run must return 200");
        assertEquals("COMPLETED", run.getBody().get("status"));

        List<ServeEvent> events = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .toList();
        assertEquals(1, events.size(),
                "ExtensionHookAdvisor must POST exactly once per registered post-hook per run");
        assertEquals("POST", events.get(0).getRequest().header("X-Hook-Phase").firstValue(),
                "post-hook dispatch sets X-Hook-Phase: POST (ExtensionHookAdvisor.java:195)");
        assertEquals(hookId, events.get(0).getRequest().header("X-Hook-Id").firstValue(),
                "X-Hook-Id header must echo the registered extension id");
        String body = events.get(0).getRequest().getBodyAsString();
        assertTrue(body.contains("\"phase\":\"POST\""),
                "webhook body must declare phase=POST");
        assertTrue(body.contains(cannedReply),
                "post-hook payload must carry the assistant reply (extractOutputText)");
    }

    // ─── §16 Case 2 — webhook errors must not fail the run (fail-safe) ───

    /**
     * Spec §16.2. Choose-fail-safe semantics. {@code dispatchHook} wraps the
     *   WebClient call in try/catch + {@code log.warn} (ExtensionHookAdvisor.java:203).
     *   A webhook returning HTTP 500 must NOT propagate to the agent run — the run
     *   completes normally (HTTP 200, status=COMPLETED), the assistant reply is
     *   unchanged, and WireMock still records the attempted call (proving the
     *   advisor tried, not that it skipped).
     */
    @Test
    void webhookReturning500_runStillCompletesAndAdvisorSwallowsError() {
        HttpHeaders auth = authenticatedHeaders("ext-failsafe-runner");

        String hookId = "ext-fail-" + UUID.randomUUID();
        String hookPath = "/hooks/fail-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath, true);
        String agentId = createAgentWithPostHook(auth, "T039 FailSafe Agent", hookId);

        String cannedReply = "T039 case 2 assistant response";
        fakeModel.respondWith(cannedReply);

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "T039 case 2 user prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, run.getStatusCode(),
                "webhook 5xx must NOT surface as a run failure — fail-safe contract");
        assertEquals("COMPLETED", run.getBody().get("status"),
                "agent_runs.status must still land COMPLETED when a post-hook errors");
        assertEquals(cannedReply, run.getBody().get("content"),
                "RunResponse.content must reflect the LLM reply, not anything from the webhook");

        long attempted = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .count();
        assertEquals(1L, attempted,
                "advisor must have ATTEMPTED the dispatch (the swallowed error is the point)");
    }

    // ─── §16 Case 3 — inactive extension is short-circuited inside dispatch ───

    /**
     * Spec §16.3. {@code ExtensionHookAdvisor.dispatchHook} loads the registration
     *   row and bails out if {@code !Boolean.TRUE.equals(extension.getActive())}
     *   (ExtensionHookAdvisor.java:184) — the WebClient call is never issued. The
     *   spec phrasing "without latency cost" is interpreted here as "without
     *   making the outbound HTTP call" rather than a wall-clock measurement
     *   (latency assertions would be flaky on shared CI hardware). Pin: WireMock
     *   sees zero requests for the inactive hook even though the agent has it
     *   listed in {@code postHooks}.
     */
    @Test
    void inactiveExtension_dispatchSkippedAndNoWebhookCall() {
        HttpHeaders auth = authenticatedHeaders("ext-inactive-runner");

        String hookId = "ext-inactive-" + UUID.randomUUID();
        String hookPath = "/hooks/inactive-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath, false);
        String agentId = createAgentWithPostHook(auth, "T039 Inactive Agent", hookId);

        fakeModel.respondWith("T039 case 3 reply");

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "T039 case 3 prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, run.getStatusCode());
        assertEquals("COMPLETED", run.getBody().get("status"));

        long calls = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .count();
        assertEquals(0L, calls,
                "advisor must short-circuit on extension.active=false; no webhook POST should be issued");
    }

    // ─── §16 Case 4 — read-only contract (mutation gap) ───

    /**
     * Spec §16.4. The spec says "Extension can read + optionally mutate prompt
     *   context; mutations persist downstream." Production reality:
     *   {@code dispatchHook} reads the prompt via {@code extractUserText} and the
     *   reply via {@code extractOutputText} and passes them as a webhook payload,
     *   but {@code .bodyToMono(String.class).block()} discards the response body.
     *   So:
     *     - READ half: pin POSITIVE (webhook receives the original user prompt).
     *     - MUTATE half: pin GAP (webhook returns a transformed payload, but the
     *       agent's RunResponse is unchanged from what the LLM produced).
     *   When the advisor learns to apply webhook returns back into the request or
     *   response, the second half of this assertion flips.
     */
    @Test
    void preHookWebhookReceivesPromptButCannotMutateLlmResponse() {
        HttpHeaders auth = authenticatedHeaders("ext-readonly-runner");

        String hookId = "ext-readonly-" + UUID.randomUUID();
        String hookPath = "/hooks/readonly-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ATTEMPTED_MUTATION_RESULT")));

        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath, true);
        String agentId = createAgentWithPreHook(auth, "T039 ReadOnly Agent", hookId);

        String userPrompt = "Original prompt T039 case 4 - " + UUID.randomUUID();
        String cannedReply = "Original LLM reply T039 case 4";
        fakeModel.respondWith(cannedReply);

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", userPrompt);
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, run.getStatusCode());
        assertEquals(cannedReply, run.getBody().get("content"),
                "GAP: webhook return value is read-and-discarded — RunResponse.content must reflect the LLM reply, NOT the webhook body. Flip when advisor consumes hook returns.");

        List<ServeEvent> events = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .toList();
        assertEquals(1, events.size(), "pre-hook must dispatch exactly once");
        assertEquals("PRE", events.get(0).getRequest().header("X-Hook-Phase").firstValue(),
                "pre-hook dispatch sets X-Hook-Phase: PRE");
        String body = events.get(0).getRequest().getBodyAsString();
        assertTrue(body.contains(userPrompt),
                "READ half of the contract: pre-hook payload must carry the original user prompt verbatim");
    }

    // ─── §16 Case 5 — SPI workflow-step lifecycle (covered elsewhere) ───

    /**
     * Spec §16.5 forwards explicitly to §10.6 ("SPI workflow-step extension:
     *   lifecycle test in §10.6"). That coverage already lives in
     *   {@code WorkflowsRuntimeTest} case 9
     *   ({@code spiExecutorExtension_whenSupportsReturnsTrue_runsExtensionInPlaceOfAgentAndRecordsOutput})
     *   which exercises {@link com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension}
     *   via a {@code TestSpiExecutor} stub stored in
     *   {@code workflow_steps.agent_id="spi::transform"}. Keeping a
     *   {@code @Disabled} placeholder here so the spec's case count for T039 (6)
     *   matches the file's case count and a future reader sees the cross-reference.
     */
    @Test
    @Disabled("T039(5): SPI workflow-step extension lifecycle is covered by WorkflowsRuntimeTest case 9 — see Javadoc.")
    void spiWorkflowStepExtensionLifecycle_coveredByWorkflowsRuntimeTest() {
        // intentional placeholder
    }

    // ─── §16 Case 6 — RBAC enforcement (admin-only mutations) ───

    /**
     * Spec §16.6. The intended contract — "configuring extensions is admin-only" — is now
     *   enforced. {@code ExtensionController.registerExtension / updateExtension /
     *   deleteExtension / validateConnection} carry {@code @PreAuthorize("hasRole('ADMIN')")};
     *   the GET handler stays open to any authenticated user (the FE picker reads it).
     *
     * <p>Test contract: a plain {@code ROLE_USER} caller receives 403 on POST and DELETE.
     * A {@code ROLE_ADMIN} caller still succeeds (the path tested implicitly by every
     * sibling {@code Extension*RuntimeTest} that registers a webhook fixture under
     * admin-bumped credentials).
     */
    @Test
    void extensionConfiguration_requiresAdmin_R1ProductionFix() {
        HttpHeaders userAuth = authenticateAs("ext-rbac-user-only", "ext-rbac-user-only@test.local",
                "pass-ext-1234", List.of("ROLE_USER"));

        String hookId = "ext-rbac-" + UUID.randomUUID();
        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", hookId);
        regBody.put("name", "RBAC enforcement probe");
        regBody.put("type", "WEBHOOK");
        regBody.put("url", "http://localhost:" + wiremock.port() + "/never");
        regBody.put("description", "T039 case 6");
        regBody.put("active", true);

        ResponseEntity<Map<String, Object>> registered = rest.exchange(
                url("/api/v1/extensions"),
                HttpMethod.POST, new HttpEntity<>(regBody, userAuth), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, registered.getStatusCode(),
                "ROLE_USER caller must be rejected by ExtensionController.registerExtension @PreAuthorize");

        ResponseEntity<Void> deleted = rest.exchange(
                url("/api/v1/extensions/" + hookId),
                HttpMethod.DELETE, new HttpEntity<>(userAuth), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, deleted.getStatusCode(),
                "ROLE_USER caller must be rejected by ExtensionController.deleteExtension @PreAuthorize");
    }

    // ─── helpers ───

    private void registerWebhook(HttpHeaders auth, String id, String url, boolean active) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("name", "T039 fixture " + id);
        body.put("type", "WEBHOOK");
        body.put("url", url);
        body.put("description", "T039 fixture");
        body.put("active", active);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "fixture precondition: webhook extension must register before being attached to an agent");
    }

    private String createAgentWithPostHook(HttpHeaders auth, String name, String hookId) {
        return createAgent(auth, name, List.of(), List.of(hookId));
    }

    private String createAgentWithPreHook(HttpHeaders auth, String name, String hookId) {
        return createAgent(auth, name, List.of(hookId), List.of());
    }

    private String createAgent(HttpHeaders auth, String name, List<String> preHooks, List<String> postHooks) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "T039 fixture");
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
        body.put("preHooks", preHooks);
        body.put("postHooks", postHooks);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before run endpoints reference it");
        return agentId;
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private HttpHeaders authenticatedHeaders(String username) {
        // Admin role is required by ExtensionController's @PreAuthorize gates on the
        // mutating endpoints (POST / PUT / DELETE / POST /validate). Tests that exercise
        // the webhook lifecycle MUST register fixtures under admin credentials.
        return authenticateAs(username, username + "@test.local", "pass-ext-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
