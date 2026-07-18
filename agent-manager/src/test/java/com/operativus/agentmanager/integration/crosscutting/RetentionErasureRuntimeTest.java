package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import com.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import com.operativus.agentmanager.control.repository.AgentAuditRepository;
import com.operativus.agentmanager.control.repository.AlertEventRepository;
import com.operativus.agentmanager.control.repository.ErasureRequestRepository;
import com.operativus.agentmanager.control.repository.GlobalSettingRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.control.service.DataRetentionService;
import com.operativus.agentmanager.control.service.ErasureOrchestrationService;
import com.operativus.agentmanager.control.service.SettingsService;
import com.operativus.agentmanager.control.service.queue.ErasureRequestJobHandler;
import com.operativus.agentmanager.core.entity.AgentAuditEntity;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import com.operativus.agentmanager.core.entity.AlertEvent;
import com.operativus.agentmanager.core.entity.ErasureRequest;
import com.operativus.agentmanager.core.entity.GlobalSetting;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage for the T055 / matrix §27.7 data-retention
 *   and GDPR-erasure surface. Pins two separate subsystems that share a common purpose
 *   (permanent removal of user data):
 *   1. {@link DataRetentionService#enforceRetentionPolicies} — time-based automatic purge of
 *      sessions and acknowledged alert events beyond per-category cutoffs.
 *   2. {@link ErasureOrchestrationService#submitAndProcess} — on-demand GDPR Article 17
 *      erasure across five per-domain handlers (sessions, memories, auditLogs, knowledge,
 *      and any other {@code ErasureHandler} beans discovered at startup).
 * State: Stateless.
 *
 * Scope: retention uses direct service invocation (not the 3 AM cron) because the
 *   {@code @Scheduled} binding is frozen at boot and cannot be re-registered via property
 *   override (decision 4.4). Erasure is driven through the service API; the job-handler
 *   {@code jobType()} contract is verified directly because queue-dispatch behaviour is
 *   already covered in {@code JobQueueRuntimeTest}.
 *
 * Matrix gaps marked {@code @Disabled}: {@link DataRetentionService} declares both
 *   {@code app.retention.runs-days} and {@code app.retention.audit-days} and logs them as
 *   effective policies, but the body of {@code enforceRetentionPolicies} only purges
 *   sessions and acknowledged alert events. Runs and audit logs are never deleted by the
 *   retention sweep. Pinned here so the gap is reachable in test output rather than
 *   hiding in the issues backlog.
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §27.7.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RetentionErasureRuntimeTest extends BaseIntegrationTest {

    @Autowired private DataRetentionService retention;
    @Autowired private ErasureOrchestrationService erasure;
    @Autowired private ErasureRequestJobHandler erasureJobHandler;

    @Autowired private SessionRepository sessionRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private AlertEventRepository alertEventRepository;
    @Autowired private AgenticMemoryRepository memoryRepository;
    @Autowired private AgenticMemoryOutboxRepository outboxRepository;
    @Autowired private AgentAuditRepository auditRepository;
    @Autowired private ErasureRequestRepository erasureRequestRepository;
    @Autowired private GlobalSettingRepository globalSettingRepository;

    // §27.7 case 1 — smoke invariant. enforceRetentionPolicies() on an empty database must
    // return a summary keyed by the two buckets the implementation actually purges
    // (sessions + alertEvents) with zero counts. If either bucket goes missing, downstream
    // monitoring queries (see getRetentionPolicies() consumers) will silently break.
    @Test
    void enforceRetentionPoliciesReturnsSummaryWithSessionsAndAlertEventsBucketsOnEmptyDb() {
        Map<String, Integer> summary = retention.enforceRetentionPolicies();

        assertNotNull(summary, "enforceRetentionPolicies must return a non-null summary map");
        assertTrue(summary.containsKey("sessions"),
                "summary must include 'sessions' bucket (pins the contract that sessions are purged)");
        assertTrue(summary.containsKey("alertEvents"),
                "summary must include 'alertEvents' bucket (pins the contract that acked alerts are purged)");
        assertEquals(0, summary.get("sessions"), "empty DB must report 0 sessions purged");
        assertEquals(0, summary.get("alertEvents"), "empty DB must report 0 alert events purged");
    }

    // §27.7 case 2 — time-based session purge. Seeds two sessions older than the default
    // 90-day sessions cutoff and two fresh sessions; after enforcement, only the old ones
    // are deleted and the summary reports count=2. updatedAt is set explicitly because the
    // service filters on that field, not createdAt.
    @Test
    void enforceRetentionPoliciesPurgesSessionsOlderThanRetentionCutoff() {
        LocalDateTime veryOld = LocalDateTime.now().minusDays(120);
        LocalDateTime fresh = LocalDateTime.now().minusDays(5);

        AgentSession old1 = persistSession("old-" + UUID.randomUUID(), "u-ret-1", veryOld);
        AgentSession old2 = persistSession("old-" + UUID.randomUUID(), "u-ret-2", veryOld);
        AgentSession fresh1 = persistSession("fresh-" + UUID.randomUUID(), "u-ret-3", fresh);
        AgentSession fresh2 = persistSession("fresh-" + UUID.randomUUID(), "u-ret-4", fresh);

        Map<String, Integer> summary = retention.enforceRetentionPolicies();

        assertEquals(2, summary.get("sessions"), "exactly the 2 old sessions should be reported as purged");
        assertFalse(sessionRepository.existsById(old1.getSessionId()), "old session 1 must be deleted");
        assertFalse(sessionRepository.existsById(old2.getSessionId()), "old session 2 must be deleted");
        assertTrue(sessionRepository.existsById(fresh1.getSessionId()), "fresh session 1 must survive");
        assertTrue(sessionRepository.existsById(fresh2.getSessionId()), "fresh session 2 must survive");
    }

    // §27.7 case 3 — alert-event purge is gated on acknowledged=true. Seeds three events:
    // (a) old + acked, (b) old + unacked, (c) fresh + acked. Only (a) should be purged —
    // this is the privacy-vs-incident-history compromise baked into the implementation
    // (unacked alerts stick around as an incident trail even past the retention window).
    @Test
    void enforceRetentionPoliciesPurgesOnlyAcknowledgedAlertEventsBeyondCutoff() {
        LocalDateTime veryOld = LocalDateTime.now().minusDays(60);
        LocalDateTime fresh = LocalDateTime.now().minusDays(5);

        AlertEvent oldAcked = persistAlertEvent("ae-old-acked-" + UUID.randomUUID(), veryOld, true);
        AlertEvent oldUnacked = persistAlertEvent("ae-old-unacked-" + UUID.randomUUID(), veryOld, false);
        AlertEvent freshAcked = persistAlertEvent("ae-fresh-acked-" + UUID.randomUUID(), fresh, true);

        Map<String, Integer> summary = retention.enforceRetentionPolicies();

        assertEquals(1, summary.get("alertEvents"),
                "only the old+acknowledged alert should be purged — old+unacked is preserved as incident trail");
        assertFalse(alertEventRepository.existsById(oldAcked.getId()), "old acked alert must be deleted");
        assertTrue(alertEventRepository.existsById(oldUnacked.getId()),
                "old UNacked alert must survive (unacknowledged events are never auto-purged)");
        assertTrue(alertEventRepository.existsById(freshAcked.getId()), "fresh acked alert must survive");
    }

    // §27.7 case 4 — DB-backed retention-days setting overrides the application.properties
    // default. Seeds app_settings row with key 'app.retention.sessions-days'='1' (via the
    // SettingsService constant, so a rename on that side breaks this test loudly), creates
    // one session older than the DB override but younger than the file default, and asserts
    // it is still purged — proving readRetentionDays reads DB first and falls back second.
    @Test
    void retentionDaysFromGlobalSettingsOverridesApplicationPropertyDefault() {
        persistGlobalSetting(SettingsService.KEY_RETENTION_SESSIONS_DAYS, "1");

        String sessionId = "override-" + UUID.randomUUID();
        persistSession(sessionId, "u-override", LocalDateTime.now().minusDays(3));

        Map<String, Integer> summary = retention.enforceRetentionPolicies();

        assertEquals(1, summary.get("sessions"),
                "session older than the DB 1-day override must be purged, "
                        + "even though the application.properties default (90d) would keep it");
        assertFalse(sessionRepository.existsById(sessionId), "the 3-day-old session must be deleted");
    }

    // §27.7 case 5 — end-to-end erasure across multiple domain handlers. Seeds a session, a
    // memory, and an audit log all keyed to the same fictitious user; submitAndProcess must
    // wipe sessions (delete) and memories (delete), redact audit username to "[REDACTED]",
    // and persist a COMPLETED ErasureRequest whose summary reports per-handler counts.
    @Test
    void submitAndProcessErasesUserSessionsAndMemoriesAndRedactsAuditLogs() {
        String userId = "erasure-target-" + UUID.randomUUID();

        persistSession("s-erase-" + UUID.randomUUID(), userId, LocalDateTime.now());
        persistMemory(userId);
        String auditId = persistAudit(userId);

        ErasureRequest result = erasure.submitAndProcess(userId, "admin");

        assertEquals(ErasureRequest.Status.COMPLETED, result.getStatus(),
                "all domain handlers succeeded, so status must be COMPLETED (not PARTIAL/FAILED)");
        assertNotNull(result.getSummary(), "summary map must be populated with per-domain counts");
        assertTrue(result.getSummary().containsKey("sessions"), "sessions handler must report into summary");
        assertTrue(result.getSummary().containsKey("memories"), "memories handler must report into summary");
        assertTrue(result.getSummary().containsKey("auditLogs"), "auditLogs handler must report into summary");

        assertEquals(0, sessionRepository.findByUserId(userId).size(),
                "every session for the target user must be physically deleted");
        assertEquals(0, memoryRepository.findByUserId(userId).size(),
                "every memory for the target user must be physically deleted");
        AgentAuditEntity redacted = auditRepository.findById(auditId).orElseThrow();
        assertEquals("[REDACTED]", redacted.getUsername(),
                "audit row must be REDACTED in place (retained for compliance traceability, username scrubbed)");
    }

    // §27.7 case 6 — erasure is idempotent. Submitting a second request for the same user
    // must still reach COMPLETED (not FAILED) and report zero counts, because every handler
    // queries by userId first and the query returns empty. This matters because ops may
    // retry an erasure that already succeeded; the endpoint must be safe to re-run.
    @Test
    void submitAndProcessIsIdempotentAcrossRepeatedSubmissions() {
        String userId = "idempotent-" + UUID.randomUUID();
        persistSession("s-idem-" + UUID.randomUUID(), userId, LocalDateTime.now());
        persistMemory(userId);

        ErasureRequest first = erasure.submitAndProcess(userId, "admin");
        assertEquals(ErasureRequest.Status.COMPLETED, first.getStatus(), "first pass must succeed");

        ErasureRequest second = erasure.submitAndProcess(userId, "admin");
        assertEquals(ErasureRequest.Status.COMPLETED, second.getStatus(),
                "second pass on fully erased user must still be COMPLETED, not FAILED");
        assertEquals(0, second.getSummary().get("sessions"), "second pass sessions count must be 0");
        assertEquals(0, second.getSummary().get("memories"), "second pass memories count must be 0");

        assertEquals(2, erasureRequestRepository.findByUserIdOrderByRequestedAtDesc(userId).size(),
                "both erasure requests must persist as an audit trail, even if the second was a no-op");
    }

    // §27.7 case 7 — the background-job handler registers under the documented jobType.
    // Pins the contract that enqueueing {"job_type": "ERASURE_REQUEST", ...} will reach
    // this handler (consumers external to this module encode that string literal).
    @Test
    void erasureRequestJobHandlerRegistersUnderErasureRequestJobType() {
        assertEquals("ERASURE_REQUEST", erasureJobHandler.jobType(),
                "jobType() return value is a wire contract — external schedulers/producers encode this literal");
        assertEquals("ERASURE_REQUEST", ErasureRequestJobHandler.JOB_TYPE,
                "public JOB_TYPE constant must match the jobType() method for compile-time producers");
    }

    // §27.7 case 8 — getRetentionPolicies returns the effective, resolvable policy set.
    // Without any DB override the four @Value-wired defaults must surface, and all four
    // keys must be present so consumers (UI / compliance dashboards) don't NPE.
    @Test
    void getRetentionPoliciesExposesAllFourBucketsUsingApplicationPropertyDefaults() {
        Map<String, Integer> policies = retention.getRetentionPolicies();

        assertTrue(policies.containsKey("sessions_days"), "sessions_days must always be exposed");
        assertTrue(policies.containsKey("runs_days"), "runs_days must always be exposed");
        assertTrue(policies.containsKey("audit_days"), "audit_days must always be exposed");
        assertTrue(policies.containsKey("alerts_days"), "alerts_days must always be exposed");

        assertTrue(policies.get("sessions_days") > 0, "sessions retention must be a positive window");
        assertTrue(policies.get("runs_days") > 0, "runs retention must be a positive window");
        assertTrue(policies.get("audit_days") > 0, "audit retention must be a positive window");
        assertTrue(policies.get("alerts_days") > 0, "alerts retention must be a positive window");
    }

    // §27.7 case 9 — time-based runs purge. Seeds two runs older than the default 180-day
    // runs cutoff and two fresh runs; after enforcement, only the old ones are deleted and
    // the summary reports count=2 under the 'runs' bucket. createdAt is @CreatedDate-managed
    // so the helper native-UPDATEs the column post-save, mirroring persistSession.
    @Test
    void enforceRetentionPoliciesPurgesRunsOlderThanRunsRetentionCutoff() {
        LocalDateTime veryOld = LocalDateTime.now().minusDays(220);
        LocalDateTime fresh = LocalDateTime.now().minusDays(5);

        String old1 = persistRun("run-old-" + UUID.randomUUID(), veryOld);
        String old2 = persistRun("run-old-" + UUID.randomUUID(), veryOld);
        String fresh1 = persistRun("run-fresh-" + UUID.randomUUID(), fresh);
        String fresh2 = persistRun("run-fresh-" + UUID.randomUUID(), fresh);

        Map<String, Integer> summary = retention.enforceRetentionPolicies();

        assertTrue(summary.containsKey("runs"),
                "summary must include 'runs' bucket once the purge body ships");
        assertEquals(2, summary.get("runs"), "exactly the 2 old runs should be reported as purged");
        assertFalse(runRepository.existsById(old1), "old run 1 must be deleted");
        assertFalse(runRepository.existsById(old2), "old run 2 must be deleted");
        assertTrue(runRepository.existsById(fresh1), "fresh run 1 must survive");
        assertTrue(runRepository.existsById(fresh2), "fresh run 2 must survive");
    }

    // §27.7 case 10 — time-based audit purge. agent_audits has no child FKs and no JPA
    // Auditing listener, so the helper sets createdAt directly on the entity before save.
    // Asserts the summary reports under 'agentAudits' (not the generic 'audit') to avoid
    // colliding with any future retention-summary bucket name collisions.
    @Test
    void enforceRetentionPoliciesPurgesAuditLogsOlderThanAuditRetentionCutoff() {
        LocalDateTime veryOld = LocalDateTime.now().minusDays(400);
        LocalDateTime fresh = LocalDateTime.now().minusDays(10);

        String oldAudit1 = persistAuditAt("u-ret-audit-1", veryOld);
        String oldAudit2 = persistAuditAt("u-ret-audit-2", veryOld);
        String freshAudit = persistAuditAt("u-ret-audit-3", fresh);

        Map<String, Integer> summary = retention.enforceRetentionPolicies();

        assertTrue(summary.containsKey("agentAudits"),
                "summary must include 'agentAudits' bucket once the purge body ships");
        assertEquals(2, summary.get("agentAudits"),
                "exactly the 2 old audits should be reported as purged");
        assertFalse(auditRepository.existsById(oldAudit1), "old audit 1 must be deleted");
        assertFalse(auditRepository.existsById(oldAudit2), "old audit 2 must be deleted");
        assertTrue(auditRepository.existsById(freshAudit), "fresh audit must survive");
    }

    // --- seeding helpers ----------------------------------------------------------------

    private AgentSession persistSession(String id, String userId, LocalDateTime updatedAt) {
        AgentSession s = new AgentSession();
        s.setSessionId(id);
        s.setUserId(userId);
        s.setOrgId("org-test");
        s.setTitle("t055 seeded");
        sessionRepository.saveAndFlush(s);
        // @PrePersist sets updatedAt = now(); override via native UPDATE so the retention
        // filter (which reads updatedAt) actually sees the desired cutoff.
        jdbc.update("UPDATE agent_sessions SET updated_at = ? WHERE session_id = ?", updatedAt, id);
        return sessionRepository.findById(id).orElseThrow();
    }

    private AlertEvent persistAlertEvent(String id, LocalDateTime firedAt, boolean acknowledged) {
        // alert_events.rule_id has an FK to alert_rules(id); seed a parent row idempotently.
        jdbc.update("""
                INSERT INTO alert_rules (id, name, metric_name, condition, threshold,
                                         window_seconds, severity, enabled, created_at, updated_at)
                VALUES ('rule-t055', 't055 rule', 'metric.t055', 'GT', 1.0, 60, 'INFO', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """);
        AlertEvent e = new AlertEvent(id, "rule-t055", 1.0, "t055 seeded", "INFO");
        e.setFiredAt(firedAt);
        e.setAcknowledged(acknowledged);
        return alertEventRepository.saveAndFlush(e);
    }

    private void persistGlobalSetting(String key, String value) {
        globalSettingRepository.saveAndFlush(new GlobalSetting(key, value, "t055 seeded override"));
    }

    private void persistMemory(String userId) {
        AgenticMemoryEntity m = new AgenticMemoryEntity();
        m.setMemoryId(UUID.randomUUID());
        m.setUserId(userId);
        m.setMemory("t055 seeded memory");
        m.setMemoryTier(AgenticMemoryEntity.MemoryTier.USER_MEMORY);
        memoryRepository.saveAndFlush(m);
    }

    private String persistAudit(String userId) {
        seedT055Agent();
        AgentAuditEntity a = new AgentAuditEntity("agent-t055", "CREATE", userId, "{\"v\":1}");
        auditRepository.saveAndFlush(a);
        return a.getId();
    }

    private String persistAuditAt(String userId, LocalDateTime createdAt) {
        seedT055Agent();
        AgentAuditEntity a = new AgentAuditEntity("agent-t055", "CREATE", userId, "{\"v\":1}");
        a.setCreatedAt(createdAt);
        auditRepository.saveAndFlush(a);
        return a.getId();
    }

    private String persistRun(String id, LocalDateTime createdAt) {
        seedT055Agent();
        AgentRun r = new AgentRun();
        r.setId(id);
        r.setAgentId("agent-t055");
        r.setStatus(RunStatus.COMPLETED);
        runRepository.saveAndFlush(r);
        // @CreatedDate on AgentRun.createdAt is written by AuditingEntityListener at persist
        // time; a native UPDATE is the only way to force the row past the retention cutoff.
        jdbc.update("UPDATE agent_runs SET created_at = ? WHERE id = ?", createdAt, id);
        return id;
    }

    // §27.7 case 11 — memory erasure also deletes linked outbox rows (M7 cascade fix).
    // The agentic_memory_outbox FK (fk_agentic_memory_outbox_memory) is NOT VALID so the DB
    // does not enforce the cascade — the application must delete outbox rows before memories.
    // Seeds a memory + two outbox rows for the erasure target and one outbox row for a
    // bystander user, then fires the erasure handler and asserts that only the target's
    // outbox rows are gone while the bystander's survives.
    @Test
    void memoryErasureHandlerDeletesOutboxRowsLinkedToErasedMemories() {
        String targetUserId = "m7-target-" + UUID.randomUUID();
        String bystanderUserId = "m7-bystander-" + UUID.randomUUID();

        UUID targetMemoryId = persistMemoryReturningId(targetUserId);
        UUID bystanderMemoryId = persistMemoryReturningId(bystanderUserId);

        UUID outbox1 = persistOutboxRow(targetMemoryId);
        UUID outbox2 = persistOutboxRow(targetMemoryId);
        UUID outboxBystander = persistOutboxRow(bystanderMemoryId);

        ErasureRequest result = erasure.submitAndProcess(targetUserId, "admin");

        assertEquals(ErasureRequest.Status.COMPLETED, result.getStatus(),
                "erasure must complete successfully even when outbox rows exist");
        assertEquals(0, memoryRepository.findByUserId(targetUserId).size(),
                "target user's memory rows must be physically deleted");
        assertFalse(outboxRepository.existsById(outbox1),
                "outbox row 1 linked to erased memory must be deleted (M7 cascade fix)");
        assertFalse(outboxRepository.existsById(outbox2),
                "outbox row 2 linked to erased memory must be deleted (M7 cascade fix)");
        assertTrue(outboxRepository.existsById(outboxBystander),
                "bystander user's outbox row must survive — erasure must be userId-scoped, not table-wide");
    }

    private UUID persistMemoryReturningId(String userId) {
        AgenticMemoryEntity m = new AgenticMemoryEntity();
        UUID id = UUID.randomUUID();
        m.setMemoryId(id);
        m.setUserId(userId);
        m.setMemory("m7 seeded memory");
        m.setMemoryTier(AgenticMemoryEntity.MemoryTier.USER_MEMORY);
        memoryRepository.saveAndFlush(m);
        return id;
    }

    private UUID persistOutboxRow(UUID memoryId) {
        UUID outboxId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agentic_memory_outbox (outbox_id, memory_id, payload, status, retry_count, created_at, updated_at)
                VALUES (?, ?, '{"event":"MEMORY_ADDED"}', 'PENDING', 0, now(), now())
                """, outboxId, memoryId);
        return outboxId;
    }

    private void seedT055Agent() {
        // agent_audits.agent_id and agent_runs.agent_id both FK to agents(id); agents.model_id
        // FKs to models(id). Seed both parent rows idempotently so retention tests can own
        // their setup without interfering with other test classes.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('fake-t055-model', 'fake-t055-model', 'fake', 'fake-t055-model',
                        true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES ('agent-t055', 't055 agent', 'fake-t055-model', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """);
    }
}
