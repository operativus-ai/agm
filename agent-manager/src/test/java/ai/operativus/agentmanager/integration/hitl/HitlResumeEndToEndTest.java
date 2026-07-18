package ai.operativus.agentmanager.integration.hitl;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: End-to-end pin for the HITL resume path. Where
 *   {@link HitlResumeRuntimeTest} stops at the HTTP-200 + Approval-row contract, this
 *   class exercises what happens AFTER the resolve VT fires — does the paused
 *   {@code agent_runs} row actually progress to {@code COMPLETED} (NOT cancelled,
 *   NOT stuck PAUSED) and does the resume avoid creating a phantom duplicate row?
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p><b>Audit ref:</b> {@code .claude/reports/audit-2.4-hitl-pause-resume-2026-05-04.md}
 * F3a (duplicate row on resume) + F4 (approveTool ScopedValue scope) +
 * <b>F18 from the post-validation revision</b> ({@code "APPROVE"} vs {@code "APPROVED"}
 * string mismatch makes every APPROVE land in the REJECT branch of
 * {@code AgentService.continueRun:549}).
 *
 * <p><b>Failure model on current main (Tier 2.4 PR 3 unmerged):</b>
 *
 * <ol>
 *   <li><b>F18 — primary fail signal.</b> {@code ApprovalService.resolveApprovalForOrg}
 *       passes {@code RunStatus.APPROVED.name()} (8 letters, "APPROVED") to
 *       {@code continueRun}. The check {@code "APPROVE".equalsIgnoreCase(action)}
 *       (7 letters) returns false, so the resume falls into the REJECT branch at
 *       {@code AgentService:573-579} and the row is finalized as {@code CANCELLED}
 *       with output {@code "User rejected the requested action."}. The
 *       {@code approveResultsInCompletedNotCancelled} case asserts {@code COMPLETED}
 *       — fails with actual=CANCELLED on current main.</li>
 *   <li><b>F3a — latent.</b> Pre-PR-3, F18 short-circuits the resume before the
 *       inner {@code run()} is ever called, so no duplicate row is inserted. The
 *       row count IS 1 today (for the wrong reason). Post-PR-3 with F18 fixed,
 *       the inner {@code run()} executes and would insert a duplicate without
 *       the F3a fix; the F3a fix binds {@code preAllocatedRunId} so the existing
 *       row is reused. The {@code resumeProducesExactlyOneRunRowPerSession} case
 *       passes both pre and post-PR-3 (different reasons) — kept as a regression
 *       guard against either failure mode.</li>
 *   <li><b>F4 — latent.</b> Same gating as F3a. Surfaces only after F18 is
 *       fixed. Without a registered tool callback in the test (out of scope —
 *       no plumbing exists in repo), F4 cannot be directly asserted here. The
 *       {@code COMPLETED} contract above is the closest proxy: if F4 is broken
 *       post-PR-3, the run re-pauses on the second tool-callback gate and never
 *       reaches COMPLETED.</li>
 * </ol>
 *
 * <p><b>FakeChatModel scripting:</b> the resume's inner {@code run()} invokes
 * {@link ai.operativus.agentmanager.integration.support.FakeChatModel} which
 * returns the default {@code "OK"} text response when not pre-scripted. The LLM
 * responding with text (no tool call) lets {@code run()} terminate as
 * {@code COMPLETED} — isolating the test signal to F18+F3a's row-state contract
 * without dragging in tool-callback scripting complexity.
 *
 * <p><b>Auth + tenant scope:</b> uses {@code registerLoginWithOrg} (canonical
 * tenant-isolation fixture) so the new tenant-scoped {@code ApprovalsController}
 * accepts the resolve request. Stacks on top of {@code fix/hitl-authz-tenant-scoping}
 * (PR 1 of this campaign).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class HitlResumeEndToEndTest extends BaseIntegrationTest {

    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        // agents.model_id FK — seed model row first so agent inserts succeed.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // Primary contract: APPROVE on a paused run must result in status=COMPLETED.
    //
    // Pre-PR-3 (current main): F18 makes "APPROVE".equalsIgnoreCase("APPROVED") return
    // false, so continueRun lands in the REJECT branch and writes status=CANCELLED.
    // This assertion FAILS today with actual=CANCELLED.
    //
    // Post-PR-3 (combined F18+F3a+F4 fix): the action-string check is corrected, the
    // inner run() executes against FakeChatModel (which returns "OK" text), the run
    // is finalized COMPLETED, and continueRun:567 propagates that status to the
    // outer (originally-paused) row.
    @Test
    void approveResultsInCompletedNotCancelled() {
        Fixture fx = seedPausedRunWithApproval("e2e-approve-completes");
        HttpHeaders auth = registerLoginWithOrg(fx.username, fx.orgId);

        ResponseEntity<Map<String, Object>> resp = postResolve(fx, auth, "APPROVED");
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "approve must succeed at the HTTP layer — 4xx here means the test fixture is misaligned with PR 1's tenant-scoping; not the F18 signal");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
                    assertEquals("COMPLETED", status,
                            "APPROVE must finalize the run as COMPLETED — actual=CANCELLED is the F18 signal "
                                    + "(\"APPROVE\".equalsIgnoreCase(\"APPROVED\") returns false; resume hits "
                                    + "the REJECT branch at AgentService:573); actual=PAUSED is the F4 signal "
                                    + "(approveTool no-op causes the resumed run to re-pause on the same tool); "
                                    + "actual=FAILED is the inner-run agent-lookup signal (resume ergonomics — "
                                    + "userId/orgId not propagated to inner run())");
                });
    }

    // Regression guard against F3a: count==1 must hold pre AND post PR 3, but for
    // different reasons. Pre-PR-3, F18 short-circuits the inner run() so no
    // duplicate is inserted (count==1, accidentally correct). Post-PR-3 with the
    // F3a fix, the inner run() reuses the original row via preAllocatedRunId
    // (count==1, intentionally correct). count==2 here would mean either:
    // PR-6 — Resume-counter contract. Approve and reject paths each increment the
    // agm.hitl.resume.total counter with their respective outcome tag. Today there's no
    // queryable signal for "how many resumes are completing successfully vs failing"
    // outside log scraping; this counter gives SREs a Prometheus dial. The tool tag
    // captures the resumed tool name (extracted from required_action) so dashboards can
    // identify which tool surfaces require the most human review.
    @Test
    void resumeIncrementsOutcomeCounter_forBothApproveAndReject() {
        // Reject path
        Fixture rej = seedPausedRunWithApproval("e2e-counter-reject");
        HttpHeaders rejAuth = registerLoginWithOrg(rej.username, rej.orgId);
        postResolve(rej, rejAuth, "REJECTED");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    String s = jdbc.queryForObject(
                            "SELECT status FROM agent_runs WHERE id = ?", String.class, rej.runId);
                    return s != null && !"PAUSED".equals(s);
                });

        // Approve path
        Fixture app = seedPausedRunWithApproval("e2e-counter-approve");
        HttpHeaders appAuth = registerLoginWithOrg(app.username, app.orgId);
        postResolve(app, appAuth, "APPROVED");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    String s = jdbc.queryForObject(
                            "SELECT status FROM agent_runs WHERE id = ?", String.class, app.runId);
                    return s != null && !"PAUSED".equals(s);
                });

        io.micrometer.core.instrument.Counter rejected = meterRegistry.find(
                ai.operativus.agentmanager.core.model.MetricConstants.HITL_RESUME_TOTAL)
                .tag("outcome", "rejected").counter();
        io.micrometer.core.instrument.Counter approved = meterRegistry.find(
                ai.operativus.agentmanager.core.model.MetricConstants.HITL_RESUME_TOTAL)
                .tag("outcome", "approved").counter();

        assertNotNull(rejected,
                "agm.hitl.resume.total{outcome=rejected} must be registered after reject path — without it, SREs cannot alert on rejected-resume rates");
        assertNotNull(approved,
                "agm.hitl.resume.total{outcome=approved} must be registered after approve path");
        assertTrue(rejected.count() >= 1.0,
                "rejected counter must have incremented at least once — got " + rejected.count());
        assertTrue(approved.count() >= 1.0,
                "approved counter must have incremented at least once — got " + approved.count());
    }

    //   (a) someone reverted the F3a fix and F18 is also fixed, exposing the
    //       AgentService.java:181-197 else-branch INSERT, OR
    //   (b) a new code path inserted an additional row.
    @Test
    void resumeProducesExactlyOneRunRowPerSession() {
        Fixture fx = seedPausedRunWithApproval("e2e-no-dup");
        HttpHeaders auth = registerLoginWithOrg(fx.username, fx.orgId);

        postResolve(fx, auth, "APPROVED");

        // Wait for the row to leave PAUSED before counting — otherwise we may
        // count before any resume work has happened.
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
                    return status != null && !"PAUSED".equals(status);
                });

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE session_id = ?", Integer.class, fx.sessionId);
        assertEquals(1, rowCount,
                "resume must produce exactly one agent_runs row per session — count=2 means a phantom "
                        + "row was inserted at AgentService.java:181-197 (audit Tier 2.4 F3a)");
    }

    // ─── helpers ───

    private record Fixture(String orgId, String username, String agentId,
                           String sessionId, String runId, String approvalId) {}

    private Fixture seedPausedRunWithApproval(String label) {
        String orgId = "org-" + label;
        String username = "user-" + label;
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        // agents.org_id MUST match the caller's orgId so post-PR-3 the resume's
        // agentRegistry.findByIdAndOrgId(agentId, orgId) succeeds. Without this,
        // the inner run() throws ResourceNotFoundException at AgentService:132,
        // and the test would fail post-PR-3 with status=FAILED instead of COMPLETED
        // — masking the F18+F3a contract.
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', ?, true, now(), now())
                """, agentId, "HITL E2E Agent " + label, orgId);

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, username, orgId, agentId);

        // The required_action payload mimics what AgentService.finalizeRunRecord
        // writes — JSON-serialized RequiredAction record (post audit Tier 2.4 F6 fix).
        // The consumer-side parser at AgentService.continueRun deserializes via Jackson
        // and reads .tool() to seed the approved-tools ScopedValue. The tool name
        // doesn't need a registered callback because FakeChatModel returns text on
        // resume (no tool call is issued), so the AugmentedToolCallbackProvider
        // gate never fires.
        String requiredAction = "{\"type\":\"TOOL_APPROVAL\",\"tool\":\"delete_database\","
                + "\"args\":\"{}\",\"approvalId\":\"" + approvalId + "\"}";

        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id,
                                        input, status, required_action, version,
                                        created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PAUSED', ?, 0, now(), now())
                """, runId, agentId, sessionId, username, orgId, "Resume the paused tool call.", requiredAction);

        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 'delete_database',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now())
                """, approvalId, runId, sessionId, agentId, "{}", username, orgId);

        return new Fixture(orgId, username, agentId, sessionId, runId, approvalId);
    }

    private ResponseEntity<Map<String, Object>> postResolve(Fixture fx, HttpHeaders auth, String decision) {
        Map<String, Object> body = Map.of("decision", decision);
        return rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                new org.springframework.core.ParameterizedTypeReference<>() {});
    }
}
