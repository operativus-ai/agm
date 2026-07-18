package com.operativus.agentmanager.integration.approvals;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.control.service.ApprovalService;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: End-to-end pin for the APPROVAL_SLA_BREACH webhook chain:
 *   {@code ApprovalService.checkApprovalSla} publishes an {@code AlertFiredEvent} with
 *   {@code ruleId="APPROVAL_SLA_BREACH"} →
 *   {@code AlertIntegrationService.onAlertFired} reads {@code findByOrgIdAndEnabledTrue} →
 *   POSTs to each integration's endpoint via {@code SsrfGuard.attemptDispatch} (hardened
 *   in PR #667).
 *   The single-row event-publish branch is already pinned by
 *   {@link ApprovalsRuntimeTest#slaScheduler_publishesAlertFiredEventForPendingOlderThan20Hours_rowStaysPending};
 *   this test pins the wire-format delivery + cross-tenant isolation downstream of the
 *   event publish.
 * State: Stateless. WireMock servers shared across tests (per-test {@code resetAll()}).
 *
 * <p>Why loopback URLs work here: PR #667 wired {@code SsrfGuard} into
 * {@code AlertIntegrationService.attemptDispatch}, and WireMock binds on {@code localhost}.
 * The harness default {@code agentmanager.alerts.ssrf.allow-loopback-urls=true}
 * (application-test.properties) allows it suite-wide, so no per-class {@code @TestPropertySource}
 * is needed (which would fork a dedicated Spring context). Production retains the strict (false)
 * default — pinned by {@code AlertIntegrationServiceSsrfTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsSlaWebhookEndToEndRuntimeTest extends BaseIntegrationTest {

    private static WireMockServer wiremockA;
    private static WireMockServer wiremockB;

    private static final String WEBHOOK_PATH = "/sla-webhook";

    @Autowired private ApprovalService approvalService;
    @Autowired private AlertIntegrationRepository integrationRepository;

    @BeforeAll
    static void startWireMocks() {
        wiremockA = new WireMockServer(options().dynamicPort());
        wiremockA.start();
        wiremockB = new WireMockServer(options().dynamicPort());
        wiremockB.start();
    }

    @AfterAll
    static void stopWireMocks() {
        if (wiremockA != null) wiremockA.stop();
        if (wiremockB != null) wiremockB.stop();
    }

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        wiremockA.resetAll();
        wiremockB.resetAll();
        wiremockA.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(200)));
        wiremockB.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(200)));
    }

    // P2.3-1 — Overdue approval in org-A triggers webhook delivery to org-A's
    // integration only. org-B has an enabled integration too, but its WireMock must
    // receive ZERO. Pins onAlertFired's findByOrgIdAndEnabledTrue scoping is honored
    // for APPROVAL_SLA_BREACH events end-to-end.
    @Test
    void pendingOver20hInOrgA_dispatchesWebhookToOrgAOnly_notToOrgB() {
        String orgA = "org-sla-webhook-A";
        String orgB = "org-sla-webhook-B";
        seedIntegration(orgA, wiremockA.baseUrl() + WEBHOOK_PATH);
        seedIntegration(orgB, wiremockB.baseUrl() + WEBHOOK_PATH);

        String overdueId = seedOldPendingApprovalViaJdbc("sla-wh-a", orgA,
                LocalDateTime.now().minusHours(22));

        approvalService.checkApprovalSla();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(1,
                        countPostsTo(wiremockA, WEBHOOK_PATH),
                        "org-A WireMock must receive exactly one POST for the overdue approval"));

        assertAll("dispatch is scoped to caller-org only",
                () -> assertEquals(0, countPostsTo(wiremockB, WEBHOOK_PATH),
                        "org-B WireMock must receive ZERO — onAlertFired filters by event.getOrgId()"),
                () -> assertNotNull(overdueId,
                        "sanity: seeded approval id reference"));
    }

    // P2.3-2 — Pin the wire-format payload. The webhook body must include the
    // approval id (as eventId) and the alertType marker ("APPROVAL_SLA_BREACH"), so
    // downstream operator tooling (PagerDuty / Slack / custom dashboards) can route
    // the alert correctly. Without this pin, a regression that renames the ruleId
    // string or drops the eventId from the payload would silently break alerting.
    @Test
    void slaWebhookPayload_includesApprovalIdAndRuleId() {
        String orgA = "org-sla-webhook-payload";
        seedIntegration(orgA, wiremockA.baseUrl() + WEBHOOK_PATH);
        String overdueId = seedOldPendingApprovalViaJdbc("sla-wh-payload", orgA,
                LocalDateTime.now().minusHours(25));

        approvalService.checkApprovalSla();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertTrue(
                        countPostsTo(wiremockA, WEBHOOK_PATH) >= 1,
                        "org-A must receive at least one POST"));

        List<LoggedRequest> requests = wiremockA.findAll(postRequestedFor(urlPathEqualTo(WEBHOOK_PATH)));
        assertTrue(requests.size() >= 1, "captured at least one request body");
        String body = requests.get(0).getBodyAsString();

        assertAll("APPROVAL_SLA_BREACH payload shape",
                () -> assertTrue(body.contains("APPROVAL_SLA_BREACH"),
                        "payload must carry the ruleId marker for operator routing: " + body),
                () -> assertTrue(body.contains(overdueId),
                        "payload must include the overdue approval id as eventId: " + body));
    }

    // P2.3-3 — Multi-tenant fan-out: two orgs each have overdue approvals. Both
    // integrations are enabled. Each WireMock receives exactly the events for its own
    // org. The integrations have the same path so any cross-leak would land
    // mis-counted on the wrong instance — the assertion catches both directions.
    @Test
    void multiTenantOverdueApprovals_eachWebhookReceivesOnlyItsOrgEvents() {
        String orgA = "org-sla-multi-fan-A";
        String orgB = "org-sla-multi-fan-B";
        seedIntegration(orgA, wiremockA.baseUrl() + WEBHOOK_PATH);
        seedIntegration(orgB, wiremockB.baseUrl() + WEBHOOK_PATH);

        seedOldPendingApprovalViaJdbc("sla-multi-a-1", orgA, LocalDateTime.now().minusHours(21));
        seedOldPendingApprovalViaJdbc("sla-multi-a-2", orgA, LocalDateTime.now().minusHours(30));
        seedOldPendingApprovalViaJdbc("sla-multi-b-1", orgB, LocalDateTime.now().minusHours(40));

        approvalService.checkApprovalSla();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertAll("fan-out is org-scoped",
                        () -> assertEquals(2, countPostsTo(wiremockA, WEBHOOK_PATH),
                                "org-A has 2 overdue rows → 2 POSTs to wiremockA"),
                        () -> assertEquals(1, countPostsTo(wiremockB, WEBHOOK_PATH),
                                "org-B has 1 overdue row → 1 POST to wiremockB")));
    }

    // ─── helpers ───

    private String seedOldPendingApprovalViaJdbc(String label, String orgId, LocalDateTime createdAt) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "SLA Webhook Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'sla-wh-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"k\":\"v\"}", label + "-user", orgId, createdAt, createdAt);
        return approvalId;
    }

    private void seedIntegration(String orgId, String endpointUrl) {
        AlertIntegration integration = new AlertIntegration();
        integration.setId(UUID.randomUUID().toString());
        integration.setOrgId(orgId);
        integration.setName("test-integration-" + orgId);
        integration.setType("WEBHOOK");
        integration.setEndpointUrl(endpointUrl);
        integration.setEnabled(true);
        integrationRepository.save(integration);
    }

    private static long countPostsTo(WireMockServer server, String path) {
        return server.findAll(postRequestedFor(urlPathEqualTo(path))).size();
    }
}
