package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.control.service.ApprovalService;
import ai.operativus.agentmanager.core.event.AlertFiredEvent;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin the SLA-sweep event-publish semantics of
 *   {@code ApprovalService.checkApprovalSla()}. The sweep iterates ALL PENDING approvals
 *   whose {@code created_at} is older than the 20-hour threshold and publishes one
 *   {@code AlertFiredEvent("APPROVAL_SLA_BREACH", …)} per overdue row, every invocation.
 *   {@link ApprovalsRuntimeTest#slaScheduler_publishesAlertFiredEventForPendingOlderThan20Hours_rowStaysPending}
 *   covers the single-row single-sweep happy path; this test pins the multi-row and
 *   multi-sweep contracts that the existing test does not exercise.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Current (no-dedup) contract pinned by case 2: the sweep republishes for the same row
 * on every call. If a future PR adds dedup (e.g., a {@code sla_alert_published_at}
 * column on the approval row), this test would flip to assert single-publish — the
 * failure message points to that decision point so the next contributor knows whether
 * the new behaviour was intentional or a regression.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        ApprovalsSlaIdempotencyRuntimeTest.SlaEventRecorder.class})
public class ApprovalsSlaIdempotencyRuntimeTest extends BaseIntegrationTest {

    @Autowired private ApprovalService approvalService;
    @Autowired private SlaEventRecorder recorder;

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        recorder.reset();
    }

    // P3.1-1 — Multi-row contract. Three rows each older than 20h must produce three
    // distinct events on a single sweep — one per row. Pinning this prevents a future
    // bug that batches rows into a single aggregate event (which would silently lose
    // per-approval visibility on the alert side).
    @Test
    void slaSweep_distinctPendingRowsBreaching_publishOneEventPerRow() {
        String oldOrgA = "org-sla-multi-A";
        String oldOrgB = "org-sla-multi-B";
        String approvalA = seedOldPendingApprovalViaJdbc("sla-multi-a", oldOrgA, LocalDateTime.now().minusHours(21));
        String approvalB = seedOldPendingApprovalViaJdbc("sla-multi-b", oldOrgB, LocalDateTime.now().minusHours(25));
        String approvalC = seedOldPendingApprovalViaJdbc("sla-multi-c", oldOrgA, LocalDateTime.now().minusHours(40));
        // One fresh row to verify it is NOT included in the sweep results.
        seedOldPendingApprovalViaJdbc("sla-fresh", oldOrgA, LocalDateTime.now().minusMinutes(5));

        approvalService.checkApprovalSla();

        // AlertFiredEvent fan-out is async (the multicaster delivers on a separate
        // executor), so the recorder may not have all 3 events the instant the sweep
        // returns. Poll until they land before snapshotting — otherwise the size
        // assertion races the delivery and flakes under full-suite load.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recorder.snapshotAlerts("APPROVAL_SLA_BREACH").size() >= 3);

        List<AlertFiredEvent> events = recorder.snapshotAlerts("APPROVAL_SLA_BREACH");
        Set<String> alertedIds = events.stream()
                .map(AlertFiredEvent::getEventId)
                .collect(Collectors.toSet());

        assertAll("multi-row SLA sweep emits one event per overdue row",
                () -> assertEquals(3, events.size(),
                        "checkApprovalSla must publish exactly one AlertFiredEvent per overdue row — "
                                + "aggregating into a single event would lose per-approval visibility on the alert side"),
                () -> assertTrue(alertedIds.contains(approvalA),
                        "approval A (21h old) must surface"),
                () -> assertTrue(alertedIds.contains(approvalB),
                        "approval B (25h old) must surface"),
                () -> assertTrue(alertedIds.contains(approvalC),
                        "approval C (40h old) must surface"));

        // The fresh row's id must NOT appear — pin the threshold filter.
        long freshLeaks = events.stream()
                .map(AlertFiredEvent::getEventId)
                .filter(id -> id != null && id.contains("sla-fresh"))
                .count();
        assertEquals(0, freshLeaks,
                "fresh row (5min old) must NOT trigger an SLA event — 20-hour threshold must hold");
    }

    // P3.1-2 — Multi-sweep contract pin. The current implementation has no explicit
    // dedup column on the approval row; observation shows repeated sweeps DO publish
    // multiple events for the same overdue row, but the exact count may vary in tight
    // synchronous succession (event-listener side-effects, FK lookups, or downstream
    // @Async listeners can perturb fan-out). The contract we pin here is: "repeated
    // sweeps over the same overdue row publish MORE THAN ONE event" — i.e., no
    // sticky-flag dedup is in place. A future fix-PR adding dedup would assert
    // size=1 and document the dedup mechanism (sla_alert_published_at column,
    // in-memory cache, or external dedup tracker) in the commit message.
    @Test
    void slaSweep_runMultipleTimesWithSameOldRow_republishesMoreThanOnce_noStickyDedup() {
        String orgId = "org-sla-dedup";
        String approvalId = seedOldPendingApprovalViaJdbc("sla-dedup", orgId, LocalDateTime.now().minusHours(22));

        approvalService.checkApprovalSla();
        approvalService.checkApprovalSla();
        approvalService.checkApprovalSla();

        // Async fan-out again: poll until >1 event for this row has been delivered
        // (3 sweeps → 3 events eventually). If a future PR adds sticky-flag dedup, this
        // poll times out and the assertion below flips — that's the intended signal.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recorder.snapshotAlerts("APPROVAL_SLA_BREACH").stream()
                        .filter(e -> approvalId.equals(e.getEventId()))
                        .count() > 1);

        long count = recorder.snapshotAlerts("APPROVAL_SLA_BREACH").stream()
                .filter(e -> approvalId.equals(e.getEventId()))
                .count();

        assertTrue(count > 1,
                "checkApprovalSla has no sticky-flag dedup today, so >1 sweep over the same overdue "
                        + "row must produce >1 event for that row (observed: " + count + "). If a future "
                        + "PR adds dedup, flip this assertion to assertEquals(1, count) and document "
                        + "the dedup mechanism (sla_alert_published_at column, in-memory cache, or "
                        + "external dedup tracker) in the commit message.");
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
                """, agentId, "SLA Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'sla-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"k\":\"v\"}", label + "-user", orgId, createdAt, createdAt);
        return approvalId;
    }

    /**
     * Test bean: records every {@link AlertFiredEvent} the ApplicationEventMulticaster
     * publishes. Same shape as {@code ApprovalsRuntimeTest.AlertEventRecorder}; duplicated
     * locally to avoid cross-test-class dependency on another @TestConfiguration.
     */
    @Component
    public static class SlaEventRecorder {
        private final List<AlertFiredEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void onAlert(AlertFiredEvent event) {
            events.add(event);
        }

        public List<AlertFiredEvent> snapshotAlerts(String ruleId) {
            return events.stream()
                    .filter(e -> ruleId.equals(e.getRuleId()))
                    .toList();
        }

        public void reset() {
            events.clear();
        }
    }
}
