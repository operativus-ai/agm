package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.control.service.ApprovalService;
import ai.operativus.agentmanager.core.event.AlertFiredEvent;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.StaleDataException;
import ai.operativus.agentmanager.core.model.ApprovalDTO;
import ai.operativus.agentmanager.core.model.DecisionPackage;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime coverage for the Approval & HITL surface beyond the
 *   resume happy-path pinned by {@link ai.operativus.agentmanager.integration.hitl.HitlResumeRuntimeTest}.
 *   Pins the TIER auto-approve policy, the SLA/cleanup schedulers, the org-scope /
 *   RBAC gaps, and the optimistic-lock guard that protects concurrent resolution.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §11 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T036.
 *
 * Implementation notes / scope decisions:
 *   - Scope overlap with T018 is intentional and minimal. T018 pins the raw
 *     PENDING → APPROVED / REJECTED HTTP contract plus the "cannot re-resolve" guard.
 *     T036 pins the policy surface around that (tier-dependent auto-approve, SLA +
 *     cleanup schedulers, org scope, RBAC, optimistic lock) — things T018 does not cover.
 *   - Schedulers ({@code checkApprovalSla}, {@code expireStaleApprovals}) are pinned to
 *     86400000ms in {@code application-test.properties} so they don't fire autonomously
 *     during a test run. The scheduled methods are invoked directly to remove jitter.
 *   - {@link AlertEventRecorder} is the canonical test hook for {@link AlertFiredEvent};
 *     the recorder is a {@link Component} inside a {@link TestConfiguration} so Spring's
 *     event bus sees it as a real listener.
 *   - Several cases pin as-shipped production GAPs (reject-does-not-cancel-run, missing
 *     RBAC on the controller, missing org_id field on the Approval entity, missing payload
 *     hash / quorum support). Each has a block comment citing 15-issues.md. Green here
 *     means the gap is observed AND documented; a future fix will flip the assertion.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        ApprovalsRuntimeTest.AlertEventRecorder.class})
public class ApprovalsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private ApprovalService approvalService;
    @Autowired private AlertEventRecorder alertRecorder;

    @BeforeEach
    void resetBeforeTest() {
        // agents.model_id FK — seed model row first; mirrors the T018 / T035 pattern.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
        alertRecorder.reset();
    }

    // §11 T036-1 — createApprovalRequest policy: TIER_1_SAFE auto-flips to APPROVED on
    // insert (see ApprovalService.createApprovalRequest line ~89 — the ternary branch);
    // any other tier stays PENDING. Pinning this guards the "safe tools never block an
    // agent run on HITL" contract that agent authors depend on when classifying tools.
    @Test
    void createApprovalRequest_autoApprovesTier1Safe_keepsTier3DestructivePending() {
        Fixture safe = seedAgentSession("tier1");
        Fixture destr = seedAgentSession("tier3");

        ApprovalDTO tier1 = approvalService.createApprovalRequest(
                safe.runId, safe.sessionId, safe.agentId,
                "safe-read-tool", "{\"q\":\"ok\"}", "auto-ok",
                safe.label + "-user", null, null,
                DecisionPackage.DecisionTier.TIER_1_SAFE);

        ApprovalDTO tier3 = approvalService.createApprovalRequest(
                destr.runId, destr.sessionId, destr.agentId,
                "drop-db-tool", "{\"db\":\"prod\"}", "needs-human",
                destr.label + "-user", null, null,
                DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE);

        assertAll("tier-driven initial status",
                () -> assertEquals("APPROVED", tier1.status().name(),
                        "TIER_1_SAFE must auto-approve on create — if PENDING here, safe tools would block agent runs waiting on HITL"),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, tier1.id()),
                        "auto-approved DTO must reflect the persisted row"),
                () -> assertEquals("PENDING", tier3.status().name(),
                        "TIER_3_DESTRUCTIVE must stay PENDING — auto-approving destructive tools would defeat the entire HITL guard"),
                () -> assertEquals("PENDING", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, tier3.id())));
    }

    // §11 T036-2 — Full lifecycle observable through the REST surface:
    //   1. PENDING row shows up in GET /pending
    //   2. POST /resolve flips it to APPROVED
    //   3. subsequent GET /pending no longer contains the row (filtered by status)
    //   4. row is persisted as APPROVED (verified via JDBC — there is intentionally no
    //      GET /api/v1/approvals/{id} endpoint on the controller; the HITL inbox UI
    //      consumes the resolve response directly and never re-fetches a single row by id)
    // Distinct from T018(a) which only pins step 2 — here we pin the list-filter contract
    // that powers the HITL inbox UI.
    @Test
    void approveLifecycle_removesFromPendingList_andRowPersistsAsApproved() {
        String orgId = "org-t036-lifecycle";
        HttpHeaders auth = registerLoginWithOrg("t036-lifecycle", orgId);
        String approvalId = seedPendingApprovalViaJdbc("lifecycle", LocalDateTime.now(), orgId).approvalId;

        ResponseEntity<Map<String, Object>> pendingBefore = rest.exchange(
                url("/api/v1/approvals/pending"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, pendingBefore.getStatusCode());
        List<?> contentBefore = (List<?>) pendingBefore.getBody().get("content");
        assertTrue(contentBefore.stream().anyMatch(c -> approvalId.equals(((Map<?, ?>) c).get("id"))),
                "PENDING approval must appear in /pending before resolution — a miss here means the paginated query lost it");

        ResponseEntity<Map<String, Object>> resolve = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resolve.getStatusCode());
        assertEquals("APPROVED", resolve.getBody().get("status"),
                "resolve response must reflect the post-flip status — the HITL inbox UI consumes this directly");

        ResponseEntity<Map<String, Object>> pendingAfter = rest.exchange(
                url("/api/v1/approvals/pending"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        List<?> contentAfter = (List<?>) pendingAfter.getBody().get("content");
        assertFalse(contentAfter.stream().anyMatch(c -> approvalId.equals(((Map<?, ?>) c).get("id"))),
                "APPROVED approval must drop from /pending — if still present, the status filter is broken and the HITL inbox would never clear");

        String persistedStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
        assertEquals("APPROVED", persistedStatus,
                "post-resolve row must persist as APPROVED — a stale row here would confuse audit/history views");
    }

    // §11 T036-3 — GAP pin: spec wording is "Reject → run transitions to CANCELLED with
    // rejection reason in payload." Production reality is: the approval row flips to
    // REJECTED, but the agent_runs / workflow_runs rows are NOT automatically cancelled.
    // ApprovalService.resolveApproval spawns a virtual thread that calls
    // agentOperations.continueRun(runId, "REJECTED") — if the run exists, it handles it;
    // if it doesn't (our fixture: run_id has no FK, row not seeded), the virtual thread
    // logs and exits. The WorkflowService route-back at line ~140 is guarded on
    // workflowRunId != null AND decision == APPROVED — REJECTED never routes back.
    // See 15-issues.md for the future cascade-cancel item.
    @Test
    void rejectApproval_flipsRowButDoesNotCreateOrCancelRunRow() {
        String orgId = "org-t036-reject";
        HttpHeaders auth = registerLoginWithOrg("t036-reject", orgId);
        Fixture fx = seedPendingApprovalViaJdbc("reject-gap", LocalDateTime.now(), orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "REJECTED", "resolved_by", "reject-user"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("REJECTED", resp.getBody().get("status"));

        // Give the fire-and-forget virtual thread a small window to exit, then assert that
        // no agent_runs or workflow_runs row was created as a side effect. This pins the
        // GAP: a reject of an unstarted/phantom run is a no-op at the run layer.
        sleepQuietly(500);

        Integer runRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE id = ?", Integer.class, fx.runId);
        assertEquals(0, runRows,
                "reject must not synthesize an agent_runs row — a count > 0 would mean the REJECTED virtual thread wrote state we didn't seed");

        Integer wfRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE session_id = ?", Integer.class, fx.sessionId);
        assertEquals(0, wfRows,
                "reject must not create or touch a workflow_runs row — REJECTED is intentionally NOT routed back to WorkflowService");
    }

    // §11 T036-4 — Multi-approver quorum (changeset 062). Row is seeded with
    // approvers_required=2 and an empty approved_by[]. First APPROVE adds the resolver
    // to approved_by, leaves status=PENDING. Second APPROVE (different resolver)
    // reaches quorum and flips status=APPROVED. A duplicate APPROVE from the same
    // resolver is rejected with 400. A single REJECT terminates regardless of quorum.
    @Test
    void multiApproverQuorum_secondApproverFlipsToApproved_duplicateRejectedWith400() {
        // ApprovalsController.resolveApproval sets resolvedBy = requireCallerUsername(),
        // not payload.resolved_by — the body-provided value is documented as not honored.
        // The authenticated username IS the resolver, so assertions must check that, not
        // a placeholder like "approver-1".
        String orgId = "org-t036-quorum";
        String resolver1 = "t036-quorum-1";
        String resolver2 = "t036-quorum-2";
        HttpHeaders auth1 = registerLoginWithOrg(resolver1, orgId);
        HttpHeaders auth2 = registerLoginWithOrg(resolver2, orgId);
        Fixture fx = seedQuorumApprovalViaJdbc("quorum", orgId, 2);

        // First APPROVE — partial; row must stay PENDING with approved_by=[resolver1].
        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth1),
                JSON_MAP);
        assertAll("partial approve",
                () -> assertEquals(HttpStatus.OK, first.getStatusCode()),
                () -> assertEquals("PENDING", first.getBody().get("status"),
                        "1 of 2 approvers — row must NOT transition to APPROVED yet"));
        Map<String, Object> afterFirst = jdbc.queryForMap(
                "SELECT status, approved_by::text, resolved_at FROM approvals WHERE id = ?", fx.approvalId);
        assertAll("partial-state row",
                () -> assertEquals("PENDING", afterFirst.get("status")),
                () -> assertTrue(((String) afterFirst.get("approved_by")).contains(resolver1),
                        "approved_by must record the first voter (" + resolver1 + ")"),
                () -> assertNull(afterFirst.get("resolved_at"),
                        "resolved_at must remain null until quorum is reached"));

        // Duplicate APPROVE from same authenticated user — must be rejected with 400.
        ResponseEntity<Map<String, Object>> dup = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth1),
                JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, dup.getStatusCode(),
                "same-resolver double-vote must be rejected — quorum requires distinct approvers");

        // Second distinct APPROVE — reaches quorum, status flips to APPROVED.
        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth2),
                JSON_MAP);
        assertAll("quorum reached",
                () -> assertEquals(HttpStatus.OK, second.getStatusCode()),
                () -> assertEquals("APPROVED", second.getBody().get("status")));
        Map<String, Object> finalRow = jdbc.queryForMap(
                "SELECT status, approved_by::text, resolved_at, resolved_by FROM approvals WHERE id = ?",
                fx.approvalId);
        assertAll("final state",
                () -> assertEquals("APPROVED", finalRow.get("status")),
                () -> assertTrue(((String) finalRow.get("approved_by")).contains(resolver1),
                        "first voter must persist in approved_by"),
                () -> assertTrue(((String) finalRow.get("approved_by")).contains(resolver2),
                        "second voter must persist in approved_by"),
                () -> assertEquals(resolver2, finalRow.get("resolved_by"),
                        "resolved_by must reflect the most-recent voter (the one who tipped quorum)"),
                () -> assertNotNull(finalRow.get("resolved_at"),
                        "resolved_at must be stamped when quorum is reached"));
    }

    // §11 T036-5 — SLA scheduler: checkApprovalSla() runs every approval-sla-check-ms
    // (900_000 prod, 86_400_000 under test so autonomous fire is suppressed). It picks up
    // PENDING approvals whose created_at is older than 20 hours and publishes an
    // AlertFiredEvent with ruleId = "APPROVAL_SLA_BREACH" and severity = "WARNING" for
    // each one. Row status stays PENDING — this is a warn, not an expire.
    @Test
    void slaScheduler_publishesAlertFiredEventForPendingOlderThan20Hours_rowStaysPending() {
        Fixture old = seedPendingApprovalViaJdbc("sla-old", LocalDateTime.now().minusHours(21));
        Fixture fresh = seedPendingApprovalViaJdbc("sla-fresh", LocalDateTime.now());

        approvalService.checkApprovalSla();

        // ApplicationEventMulticaster is async (AgentManagerConfig.java:45 wires it
        // to applicationTaskExecutor), so eventPublisher.publishEvent returns before the
        // recorder's @EventListener fires. Poll the recorder until the breach event lands.
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    List<AlertFiredEvent> b = alertRecorder.events.stream()
                            .filter(e -> "APPROVAL_SLA_BREACH".equals(e.getRuleId()))
                            .toList();
                    assertEquals(1, b.size(),
                            "exactly one SLA breach must fire — if 0, the 20h filter never matched; if 2, the fresh fixture leaked in");
                });

        List<AlertFiredEvent> breaches = alertRecorder.events.stream()
                .filter(e -> "APPROVAL_SLA_BREACH".equals(e.getRuleId()))
                .toList();
        assertEquals(old.approvalId, breaches.get(0).getEventId(),
                "eventId must carry the breaching approval id — downstream AlertingService routes by this field, a mismatch would misdirect notifications");
        assertEquals("WARNING", breaches.get(0).getSeverity());

        assertAll("row state untouched by SLA warn",
                () -> assertEquals("PENDING", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, old.approvalId),
                        "SLA check must NOT flip the row — expire is a separate scheduler at 24h"),
                () -> assertEquals("PENDING", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, fresh.approvalId),
                        "fresh row must not be touched"));
    }

    // §11 T036-6 — Cleanup scheduler: expireStaleApprovals() runs every
    // approval-cleanup-ms (3_600_000 prod, 86_400_000 test). PENDING approvals older than
    // 24 hours are flipped to EXPIRED with resolved_by = "SYSTEM_GC" and resolved_at set.
    // Rows newer than the cutoff are untouched; rows already in a terminal state are
    // ignored (only PENDING is swept).
    @Test
    void cleanupScheduler_expiresPendingOlderThan24Hours_leavesFreshAndTerminalUntouched() {
        Fixture stale = seedPendingApprovalViaJdbc("gc-stale", LocalDateTime.now().minusHours(25));
        Fixture fresh = seedPendingApprovalViaJdbc("gc-fresh", LocalDateTime.now());

        approvalService.expireStaleApprovals();

        Map<String, Object> staleRow = jdbc.queryForMap(
                "SELECT status, resolved_by, resolved_at FROM approvals WHERE id = ?", stale.approvalId);
        assertAll("stale pending expired",
                () -> assertEquals("EXPIRED", staleRow.get("status"),
                        "stale PENDING must flip to EXPIRED — if still PENDING, the 24h filter never matched and the inbox will grow unbounded"),
                () -> assertEquals("SYSTEM_GC", staleRow.get("resolved_by"),
                        "resolved_by must be SYSTEM_GC — a human username here would confuse audit trails by attributing the expiry to a real user"),
                () -> assertNotNull(staleRow.get("resolved_at"),
                        "resolved_at must be stamped on expiry — NULL here breaks audit queries that rely on the column to detect closure"));

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fresh.approvalId),
                "fresh pending row must NOT expire — cutoff is sharp, an off-by-one on the filter would clear fresh HITL requests prematurely");
    }

    // §11 T036-7 — RBAC enforcement. ApprovalsController.resolveApproval carries
    // @PreAuthorize("hasRole('ADMIN') or hasRole('APPROVER')"). A bare ROLE_USER caller
    // is rejected at the method-security gate (AccessDeniedException → 403); the row
    // MUST stay PENDING because the rejection happens before ApprovalService runs.
    // R3 production fix. The APPROVER role is newly introduced by this PR — admin-or-approver
    // is the right shape (vs admin-only) because "resolve a HITL" is conceptually distinct
    // from "administer the platform" and future per-org approver delegation becomes additive.
    @Test
    void resolveByNonApprover_returns403_R3ProductionFix() {
        // Register a ROLE_USER-only principal (no ADMIN, no APPROVER) and bind to an org so
        // tenant-scope resolution still succeeds — the rejection must be from @PreAuthorize,
        // not from a missing orgId. Inlines the registerLoginWithOrg pattern with a custom
        // roles list because the shared helper grants ROLE_USER + ROLE_ADMIN.
        String orgId = "org-t036-rbac";
        HttpHeaders nonApprover = authenticateAs("t036-rbac-plainuser",
                "t036-rbac-plainuser@test.local", "pass-r3-1234", List.of("ROLE_USER"));
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ?", orgId, "t036-rbac-plainuser");
        // Re-login so the JWT carries the new org_id claim.
        var login = new ai.operativus.agentmanager.core.model.AuthModels.LoginRequest(
                "t036-rbac-plainuser", "pass-r3-1234");
        ResponseEntity<ai.operativus.agentmanager.core.model.AuthModels.JwtResponse> reLogin =
                rest.postForEntity(url("/api/auth/login"), login,
                        ai.operativus.agentmanager.core.model.AuthModels.JwtResponse.class);
        nonApprover.setBearerAuth(reLogin.getBody().token());

        Fixture fx = seedPendingApprovalViaJdbc("rbac-gap", LocalDateTime.now(), orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED", "resolved_by", "plainuser"), nonApprover),
                JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER caller must be rejected by ApprovalsController.resolveApproval @PreAuthorize");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.approvalId),
                "row must stay PENDING — rejection happens before ApprovalService runs");
    }

    // §11 T036-8 — Payload-hash integrity is a spec concept with no production shape:
    // the approvals table has no hash column (see 001-schema.sql §23 — id, run_id, session,
    // agent, status, tool_name, tool_arguments JSONB, requested_by, resolved_by,
    // contextual_message, resolved_at, decision_tier, reasoning_trace, impact_assessment,
    // version, created_at, updated_at). ApprovalService never computes or verifies a hash.
    // Asserting tamper detection would fabricate a non-existent contract.
    // §11 T036-8 — payload-hash tamper detection. ApprovalService.createApprovalRequest
    // computes SHA-256(toolName + ":" + toolArguments) at creation time and stores it on
    // the row. resolveApprovalForOrg re-computes the hash and rejects the transition if it
    // differs from the stored value, defending against direct-DB / malicious-admin
    // mutation of what the human is approving. We simulate the tamper by seeding the row
    // with a payload_hash that does NOT match the stored tool_arguments — the resolve
    // call must return 400 and the row must stay PENDING with version unchanged.
    @Test
    void payloadHashTamperDetection_rejectsResolveAndKeepsRowPending() {
        String orgId = "org-t036-tamper";
        HttpHeaders auth = registerLoginWithOrg("t036-tamper", orgId);
        Fixture base = seedAgentSession("tamper");
        String approvalId = "approval-tamper-" + UUID.randomUUID();
        String wrongHash = "0".repeat(64); // valid VARCHAR(64) but cannot match any real digest

        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       payload_hash, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, now(), now(), 0)
                """,
                approvalId, base.runId, base.sessionId, base.agentId,
                "{\"db\":\"prod\"}", "tamper-user", orgId, wrongHash);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED", "resolved_by", "tamper-user"), auth),
                JSON_MAP);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, version, resolved_by, resolved_at FROM approvals WHERE id = ?", approvalId);

        assertAll("tamper detection rejects the resolve",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "BusinessValidationException must surface as 400 — payload mismatch is a precondition violation"),
                () -> assertEquals("PENDING", row.get("status"),
                        "row must stay PENDING — a tampered approval must NOT transition to APPROVED"),
                () -> assertEquals(0, ((Number) row.get("version")).intValue(),
                        "no save happened — version stays at 0"),
                () -> assertNull(row.get("resolved_by"),
                        "resolved_by must remain null — the resolve was rejected before mutation"),
                () -> assertNull(row.get("resolved_at"),
                        "resolved_at must remain null"));
    }

    // §11 T036-9 — Org scope enforcement: ApprovalsController resolves the caller's
    // orgId via CallerContext.resolveCallerOrgId (reads SecurityContextHolder →
    // UserDetailsImpl.getOrgId, set from the JWT claim by TenantContextFilter). It passes
    // that to ApprovalService.getAllPendingApprovals(orgId, pageable), which delegates
    // to ApprovalRepository.findByStatusAndOrgId — the canonical tenant-isolation
    // mechanism on this surface. (X-Org-Id header is no longer consulted; the legacy
    // Hibernate @Filter("tenantFilter") was removed because it never reliably activated
    // under Spring Boot 4 OSIV.) This test logs in as a user bound to orgA via
    // registerLoginWithOrg, seeds rows under orgA + orgB via JDBC, and asserts only the
    // orgA row surfaces in /pending.
    @Test
    void listPendingApprovals_respectsOrgScope() {
        HttpHeaders auth = registerLoginWithOrg("t036-orgscope", "orgA");
        Fixture orgA = seedPendingApprovalViaJdbc("org-a", LocalDateTime.now(), "orgA");
        Fixture orgB = seedPendingApprovalViaJdbc("org-b", LocalDateTime.now(), "orgB");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending?size=50"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        List<String> ids = content.stream().map(m -> (String) m.get("id")).toList();

        assertAll("org scope enforced",
                () -> assertTrue(ids.contains(orgA.approvalId),
                        "orgA row must appear when caller's X-Org-Id is orgA"),
                () -> assertFalse(ids.contains(orgB.approvalId),
                        "orgB row must NOT appear — ApprovalRepository.findByStatusAndOrgId scopes the query to the caller's org"));
    }

    // §11 T036-10 — Approval entity carries @Version (see Approval.java line 80); JPA
    // optimistic locking is the concurrency guard. Two simultaneous resolveApproval
    // calls on the same PENDING row: one commits first (flipping status + bumping
    // version), the second's save hits ObjectOptimisticLockingFailureException OR reads
    // the already-flipped status and throws BusinessValidationException ("Cannot resolve
    // in state: <APPROVED|REJECTED>"). Either outcome keeps the row in EXACTLY ONE
    // terminal state — the invariant worth pinning.
    @Test
    void concurrentResolve_exactlyOneWins_rowEndsInSingleTerminalState() throws Exception {
        String orgId = "concurrency-org";
        Fixture fx = seedPendingApprovalViaJdbc("concurrency", LocalDateTime.now(), orgId);

        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();
        AtomicReference<String> winnerDecision = new AtomicReference<>();

        Thread tA = Thread.ofVirtual().unstarted(() -> {
            awaitGate(gate);
            try {
                approvalService.resolveApprovalForOrg(fx.approvalId, "APPROVED", "worker-A", orgId);
                winnerDecision.compareAndSet(null, "APPROVED");
            } catch (Throwable t) { errA.set(t); }
        });
        Thread tB = Thread.ofVirtual().unstarted(() -> {
            awaitGate(gate);
            try {
                approvalService.resolveApprovalForOrg(fx.approvalId, "REJECTED", "worker-B", orgId);
                winnerDecision.compareAndSet(null, "REJECTED");
            } catch (Throwable t) { errB.set(t); }
        });
        tA.start(); tB.start();
        gate.countDown(); // release both at once
        assertTrue(tA.join(java.time.Duration.ofSeconds(10)));
        assertTrue(tB.join(java.time.Duration.ofSeconds(10)));

        int winners = (errA.get() == null ? 1 : 0) + (errB.get() == null ? 1 : 0);
        assertEquals(1, winners,
                "exactly one resolve must succeed — 0 means both hit the guard (dead state), 2 means the optimistic lock failed and double-spend is possible");

        Throwable loserErr = errA.get() != null ? errA.get() : errB.get();
        assertNotNull(loserErr, "loser thread must surface an error");
        assertTrue(
                loserErr instanceof StaleDataException
                || loserErr instanceof BusinessValidationException
                || loserErr instanceof org.springframework.orm.ObjectOptimisticLockingFailureException,
                "loser must throw StaleDataException (optimistic-lock path) or BusinessValidationException (read-after-commit path) — got " + loserErr.getClass().getName());

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertTrue("APPROVED".equals(finalStatus) || "REJECTED".equals(finalStatus),
                "row must end in exactly one terminal state — got " + finalStatus);
        assertEquals(winnerDecision.get(), finalStatus,
                "persisted status must equal the winning thread's decision");
    }

    // ─── helpers ───

    private record Fixture(String approvalId, String agentId, String sessionId, String runId, String label) {}

    private Fixture seedAgentSession(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Approval Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        return new Fixture(/*approvalId*/null, agentId, sessionId, runId, label);
    }

    private Fixture seedPendingApprovalViaJdbc(String label, LocalDateTime createdAt) {
        return seedPendingApprovalViaJdbc(label, createdAt, null);
    }

    /**
     * JDBC-insert path for pre-existing PENDING rows. We bypass ApprovalService here so
     * tests can (a) set org_id directly (entity doesn't expose it — see 15-issues.md),
     * (b) set created_at to an arbitrary past timestamp for the SLA / cleanup tests.
     */
    private Fixture seedPendingApprovalViaJdbc(String label, LocalDateTime createdAt, String orgId) {
        Fixture base = seedAgentSession(label);
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, 0)
                """,
                approvalId, base.runId, base.sessionId, base.agentId,
                "{\"db\":\"prod\"}", label + "-user", orgId, createdAt, createdAt);

        return new Fixture(approvalId, base.agentId, base.sessionId, base.runId, label);
    }

    /**
     * T036(4) seed helper. Inserts a PENDING approval requiring N approvers with an
     * empty approved_by JSONB array. payload_hash is left NULL so the T036(8) tamper
     * verify path is skipped (legacy-row code path). Org-scoped.
     */
    private Fixture seedQuorumApprovalViaJdbc(String label, String orgId, int approversRequired) {
        Fixture base = seedAgentSession(label);
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       approvers_required, approved_by,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?,
                        ?, '[]'::jsonb,
                        now(), now(), 0)
                """,
                approvalId, base.runId, base.sessionId, base.agentId,
                "{\"db\":\"prod\"}", "quorum-user", orgId, approversRequired);
        return new Fixture(approvalId, base.agentId, base.sessionId, base.runId, label);
    }

    private static void awaitGate(CountDownLatch gate) {
        try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records AlertFiredEvents published during a test for §11 T036-5. Imported via
     * {@code @Import(AlertEventRecorder.class)} on the test class — Spring registers it
     * as a bean and its {@code @EventListener} method hooks into the
     * ApplicationEventMulticaster. {@link #reset()} is called from {@code @BeforeEach}
     * to keep cases isolated — events from a prior case would otherwise leak.
     */
    @Component
    public static class AlertEventRecorder {
        final List<AlertFiredEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void onAlert(AlertFiredEvent event) {
            events.add(event);
        }

        public void reset() {
            events.clear();
        }
    }
}
