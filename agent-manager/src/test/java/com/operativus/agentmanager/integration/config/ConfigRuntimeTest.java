package com.operativus.agentmanager.integration.config;

import com.operativus.agentmanager.control.repository.AgentAuditRepository;
import com.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import com.operativus.agentmanager.control.repository.GlobalSettingRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.entity.AgentAuditEntity;
import com.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import com.operativus.agentmanager.core.entity.GlobalSetting;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import com.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import com.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import com.operativus.agentmanager.integration.support.SchedulerTestSupport;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage for T048 — the Config + Compliance +
 *   Retention surface. Pins:
 *   - {@link com.operativus.agentmanager.control.controller.ConfigController} static
 *     template endpoints ({@code /api/config/templates},
 *     {@code /api/config/team-templates}, {@code /api/config/workflow-templates}).
 *   - {@link com.operativus.agentmanager.control.service.DataRetentionService#enforceRetentionPolicies}
 *     positive purge paths (sessions, acknowledged alert events), the
 *     {@code GlobalSetting}-override path, and the matrix §27.7 gaps (runs + audits are
 *     NOT purged despite the {@code app.retention.runs-days} /
 *     {@code app.retention.audit-days} properties existing; unacked alerts are preserved).
 *   - {@link com.operativus.agentmanager.control.service.ErasureOrchestrationService#submitAndProcess}
 *     round-trip through the legacy sync endpoint
 *     ({@code DELETE /api/compliance/erase/{userId}}): sessions + runs + memories are
 *     deleted, audit rows are redacted (not deleted), and a second pass is idempotent.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T048 and the matrix
 * §27.7 (retention & erasure) + §26-adjacent config surface.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@code DataRetentionService.enforceRetentionPolicies()} purges
 *     {@code agent_sessions}, acknowledged {@code alert_events}, {@code agent_runs}, and
 *     {@code agent_audits} past their configured windows ({@code app.retention.sessions-days},
 *     {@code runs-days}, {@code audit-days}, {@code alerts-days}). Audit deletes go through the
 *     trg_agent_audits_immutable trigger (changeset 029) by setting
 *     {@code agm.audit_immutability_bypass='true'} on the current transaction. The one
 *     shape still intentionally as-shipped is that UNACKED alert events survive even past
 *     the window — case (a) pins that gap.
 *   - The scheduled cron on {@code enforceRetentionPolicies} is
 *     {@code @Scheduled(cron = "0 0 3 * * ?")}. We drive it via
 *     {@link SchedulerTestSupport#tickDataRetention} rather than property overrides
 *     (decision 4.4).
 *   - {@code ErasureOrchestrationService.submitAndProcess} is synchronous (runs all 4
 *     handlers inline under one {@code @Transactional}). We exercise it via the legacy
 *     sync endpoint {@code DELETE /api/compliance/erase/{userId}} rather than
 *     {@code POST /api/compliance/erasure-requests} (which enqueues an async
 *     {@code ERASURE_REQUEST} job and requires polling). The sync path is a cleaner seam
 *     for asserting observable wipe behavior.
 *   - {@code AuditErasureHandler} REDACTS the username (sets it to {@code [REDACTED]})
 *     rather than deleting the audit row. Matrix §27.7 calls this out ("audit row
 *     records the erasure") — currently the {@link com.operativus.agentmanager.core.entity.ErasureRequest}
 *     row is the canonical audit artifact for the erasure itself, NOT a row in
 *     {@code agent_audits}. Case (f) pins the aspirational "audit row emitted on
 *     erasure completion" as {@code @Disabled}.
 *   - {@code agent_audits.agent_id} has an FK with no cascade (001-schema.sql:187) —
 *     seeding an audit row requires an {@code agents} row. The helper {@link #seedAgentRow}
 *     handles the pre-seed (same pattern as T046).
 *   - {@link com.operativus.agentmanager.control.controller.ComplianceController}
 *     routes are {@code @PreAuthorize("hasRole('ADMIN')")} (except export which is
 *     self-or-admin). A {@code ROLE_USER} caller is denied with 403 via
 *     {@link com.operativus.agentmanager.core.exception.GlobalExceptionHandler}'s
 *     {@code AccessDeniedException} handler (pinned in
 *     {@link com.operativus.agentmanager.integration.models.ModelsRuntimeTest}); we do
 *     not re-pin that negative path here — this suite exercises only the happy paths
 *     with {@code ROLE_ADMIN}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, SchedulerTestSupport.class})
public class ConfigRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private SessionRepository sessionRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private AgenticMemoryRepository memoryRepository;
    @Autowired private AgentAuditRepository auditRepository;
    @Autowired private GlobalSettingRepository globalSettingRepository;
    @Autowired private SchedulerTestSupport scheduler;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // T048 Case (a) — Matrix §27.7 retention sweep.
    // Positive: a session with updated_at > retention window, an acknowledged alert_event
    // past its window, a run past app.retention.runs-days, and an audit row past
    // app.retention.audit-days are all deleted on tickDataRetention(). The retention sweep
    // authorizes its audit DELETE via the agm.audit_immutability_bypass session flag
    // (trg_agent_audits_immutable, changeset 029).
    // Gap pin (the one shape still intentionally as-shipped):
    //   - an UNACKED alert_event past its window is preserved (only acknowledged alerts
    //     are eligible — DataRetentionService.enforceRetentionPolicies:85).
    @Test
    void retentionSweepPurgesOldSessionsAckAlertsRunsAndAuditsButLeavesUnackedAlerts() {
        String victim = "retention-victim";

        // Session at -120d — past the 90d sessions-days window.
        String staleSessionId = seedSession(victim, LocalDateTime.now().minusDays(120));
        // Session at -1d — well within the window.
        String freshSessionId = seedSession(victim, LocalDateTime.now().minusDays(1));

        // alert_events.rule_id → alert_rules(id) is a non-null FK; seed the rule first.
        String ruleId = "rule-retention-" + shortUuid();
        seedAlertRule(ruleId);

        // Acknowledged alert event at -45d — past the 30d alerts-days window.
        // Seeded via SQL: the AlertEvent entity moved to the enterprise artifact,
        // but the table (changeset 009) + the retention sweep stay Core.
        String stalePurgeableId = "evt-stale-" + shortUuid();
        seedAlertEvent(stalePurgeableId, ruleId, true, 45);
        // Unacknowledged alert event at -45d — past the window, but NOT acknowledged.
        String staleUnackedId = "evt-unack-" + shortUuid();
        seedAlertEvent(staleUnackedId, ruleId, false, 45);

        // Run at -200d — past the 180d runs-days setting; purged by the retention sweep.
        String staleRunId = seedRun(victim, LocalDateTime.now().minusDays(200));

        // Audit row at -400d — past the 365d audit-days setting; purged by the retention
        // sweep (DataRetentionService sets agm.audit_immutability_bypass on the current
        // transaction before DELETE so trg_agent_audits_immutable allows it).
        String auditedAgentId = "agent-retention-" + shortUuid();
        seedAgentRow(auditedAgentId);
        AgentAuditEntity staleAudit = new AgentAuditEntity(
                auditedAgentId, "CREATE", victim, "{\"seeded\":true}");
        staleAudit.setCreatedAt(LocalDateTime.now().minusDays(400));
        auditRepository.save(staleAudit);

        scheduler.tickDataRetention();

        // Positive: stale session + acked-stale alert are purged.
        assertTrue(sessionRepository.findById(staleSessionId).isEmpty(),
                "session with updated_at past sessions-days must be purged on tickDataRetention");
        assertEquals(0, alertEventCount(stalePurgeableId),
                "acknowledged alert_event past alerts-days must be purged on tickDataRetention");

        // Fresh session survives (sanity).
        assertTrue(sessionRepository.findById(freshSessionId).isPresent(),
                "session within the retention window must survive");

        // Positive (cont'd): stale run + stale audit row are both purged.
        Integer runSurvivors = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE id = ?", Integer.class, staleRunId);
        assertEquals(0, runSurvivors,
                "runs row past app.retention.runs-days must be purged on tickDataRetention (DataRetentionService.deleteByCreatedAtBefore)");
        assertTrue(auditRepository.findById(staleAudit.getId()).isEmpty(),
                "audit row past app.retention.audit-days must be purged on tickDataRetention (bypass flag on the sweep transaction lets the DELETE pass trg_agent_audits_immutable)");

        // Gap pin: UNACKNOWLEDGED alert_event survives even past the window.
        assertEquals(1, alertEventCount(staleUnackedId),
                "UNACKNOWLEDGED alert_event survives even past the window — the retention sweep deletes only acknowledged=true rows. Removal here would indicate the acknowledged-only guard was loosened — flip the assertion and the class Javadoc.");
    }

    // T048 Case (b) — Matrix §27.7 (retention) + §26 case 4 (global_settings override is
    // respected at runtime). Insert a DB override for sessions-days=30, seed a session at
    // -45d (past 30d but within the 90d default), and assert the sweep purges it — proof
    // that readRetentionDays() consults globalSettingRepository BEFORE the @Value fallback.
    @Test
    void retentionReadsGlobalSettingsOverrideForSessionsDays() {
        String victim = "retention-override-victim";
        String midlifeSessionId = seedSession(victim, LocalDateTime.now().minusDays(45));

        // Override: sessions-days=30 → the -45d row is now past cutoff.
        GlobalSetting override = new GlobalSetting(
                "app.retention.sessions-days", "30",
                "T048 test override — shortens the retention window to expose the -45d row");
        globalSettingRepository.save(override);

        scheduler.tickDataRetention();

        assertTrue(sessionRepository.findById(midlifeSessionId).isEmpty(),
                "with app.retention.sessions-days=30 in global_settings, a session updated 45d ago must be purged. Survival here would mean readRetentionDays() ignored the DB override and kept the 90d @Value fallback — a regression in the SSOT-via-DB path.");
    }

    // T048 Case (c) — Matrix §27.7 erasure happy path through the legacy sync endpoint.
    // DELETE /api/compliance/erase/{userId} invokes ErasureOrchestrationService.submitAndProcess
    // synchronously, so we can assert wipe behavior without polling. Verifies all four
    // ErasureHandler implementations:
    //   - SessionErasureHandler deletes user's runs + sessions.
    //   - KnowledgeErasureHandler returns 0 (no knowledge owned by the user in this fixture).
    //   - MemoryErasureHandler deletes the user's agentic memory rows.
    //   - AuditErasureHandler REDACTS the audit username to "[REDACTED]" (does NOT delete).
    // The response body carries the per-domain summary from the ErasureRequest.summary JSONB.
    @Test
    void erasureViaLegacyEndpoint_wipesSessionsRunsMemoriesAndRedactsAudits() {
        String adminUser = "erasure-admin-" + shortUuid();
        HttpHeaders adminAuth = authenticateAs(adminUser, adminUser + "@test.local",
                "pass-erase-1234", List.of("ROLE_ADMIN"));

        String victim = "erasure-victim-" + shortUuid();
        // requireSameOrgOrSelf resolves the target via findByUsername and 404s if absent
        // (existence-leak protection). Register the victim in the admin's org so the gate
        // clears and the erasure handlers run against the seeded data below.
        authenticateAs(victim, victim + "@test.local", "pass-erase-victim-1234", List.of("ROLE_USER"));

        String sessionId = seedSession(victim, LocalDateTime.now().minusHours(1));
        String runId = seedRun(victim, LocalDateTime.now().minusHours(1));
        UUID memoryId = seedMemory(victim);
        String agentId = "agent-erase-" + shortUuid();
        seedAgentRow(agentId);
        AgentAuditEntity audit = new AgentAuditEntity(agentId, "CREATE", victim, "{\"seeded\":true}");
        AgentAuditEntity savedAudit = auditRepository.save(audit);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/compliance/erase/" + victim),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "DELETE /api/compliance/erase/{userId} must succeed for ROLE_ADMIN. 500 here could mean the @PreAuthorize failed (is the role string exactly 'ROLE_ADMIN'?) or a handler threw — inspect the logs.");

        Map<String, Object> summary = resp.getBody();
        assertNotNull(summary, "response body carries the ErasureRequest.summary map");
        // sessions handler returns sessions.size() + runs.size() = 1 + 1 = 2
        assertEquals(2, ((Number) summary.get("sessions")).intValue(),
                "SessionErasureHandler wipes runs + sessions and returns the combined count (1 session + 1 run = 2)");
        assertEquals(1, ((Number) summary.get("memories")).intValue(),
                "MemoryErasureHandler deletes the 1 seeded agentic_memories row");
        assertEquals(1, ((Number) summary.get("auditLogs")).intValue(),
                "AuditErasureHandler redacts the 1 audit row with username=victim");
        assertTrue(summary.containsKey("knowledge"),
                "KnowledgeErasureHandler always reports; 0 here is fine (no KB rows seeded)");

        assertTrue(sessionRepository.findById(sessionId).isEmpty(),
                "session owned by victim must be deleted");
        // RunRepository.findById is ambiguous (JpaRepository vs RunOperations overloads) —
        // drop to JDBC to assert absence cleanly.
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE id = ?", Integer.class, runId).intValue(),
                "run owned by victim must be deleted");
        assertTrue(memoryRepository.findById(memoryId).isEmpty(),
                "agentic_memories row owned by victim must be deleted");

        // Audit redaction (not deletion).
        AgentAuditEntity reloaded = auditRepository.findById(savedAudit.getId()).orElseThrow(() ->
                new AssertionError("audit row must SURVIVE erasure — AuditErasureHandler redacts username, it does NOT delete the row"));
        assertEquals("[REDACTED]", reloaded.getUsername(),
                "AuditErasureHandler replaces username with the literal '[REDACTED]' sentinel (handler line 23)");
    }

    // T048 Case (d) — Matrix §27.7 erasure idempotency: a second pass over the same userId
    // cleanly returns zero counts across every handler (sessions/runs already gone,
    // memories already gone, no unredacted audits remain, no KB owned). No exceptions,
    // no partial status.
    @Test
    void erasureIsIdempotent_secondPassProducesZeroCounts() {
        String adminUser = "erasure-admin-idem-" + shortUuid();
        HttpHeaders adminAuth = authenticateAs(adminUser, adminUser + "@test.local",
                "pass-erase-1234", List.of("ROLE_ADMIN"));

        String victim = "erasure-idem-" + shortUuid();
        // Register the victim in the admin's org so requireSameOrgOrSelf clears (see sibling
        // test). Erasure redacts/anonymizes — it does not delete the user row — so the second
        // idempotent pass still resolves the victim and returns zero counts.
        authenticateAs(victim, victim + "@test.local", "pass-erase-victim-1234", List.of("ROLE_USER"));
        seedSession(victim, LocalDateTime.now().minusHours(1));
        seedMemory(victim);
        String agentId = "agent-erase-idem-" + shortUuid();
        seedAgentRow(agentId);
        auditRepository.save(new AgentAuditEntity(agentId, "CREATE", victim, "{\"seeded\":true}"));

        // First pass: non-zero counts.
        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/compliance/erase/" + victim),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertTrue(((Number) first.getBody().get("memories")).intValue() >= 1,
                "first pass must actually erase something — otherwise this test's precondition is broken");

        // Second pass: every handler returns 0 (no state left to erase).
        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/compliance/erase/" + victim),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, second.getStatusCode(),
                "second pass must succeed, not 500 — handlers must handle empty input cleanly");
        Map<String, Object> sum = second.getBody();
        assertEquals(0, ((Number) sum.get("sessions")).intValue(),
                "second-pass sessions count must be 0 — no sessions/runs remain for victim");
        assertEquals(0, ((Number) sum.get("memories")).intValue(),
                "second-pass memories count must be 0 — no agentic_memories rows remain");
        assertEquals(0, ((Number) sum.get("auditLogs")).intValue(),
                "second-pass auditLogs count must be 0 — audit.username was already redacted to '[REDACTED]', so findByUsername(victim) returns empty");
        assertFalse(sum.containsKey("sessions_error"),
                "idempotent path must not surface handler errors in the summary");
    }

    // T048 Case (e) — Config template endpoints feed the UI wizard for "Create Agent /
    // Team / Workflow". They return pure static lists from *Template.builtInTemplates() —
    // no DB, no state — but SecurityConfig's anyRequest().authenticated() still guards
    // them (they are NOT in the publicPaths list). Pin: with a ROLE_USER bearer token,
    // all three endpoints return 200 with a non-empty list.
    @Test
    void configTemplateEndpointsReturnBuiltInTemplates() {
        String username = "config-reader-" + shortUuid();
        HttpHeaders auth = authenticateAs(username, username + "@test.local",
                "pass-cfg-1234", List.of("ROLE_USER"));

        ResponseEntity<List<Map<String, Object>>> agents = rest.exchange(
                url("/api/config/templates"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, agents.getStatusCode(),
                "GET /api/config/templates must return 200 — pure static list from AgentTemplate.builtInTemplates() behind the default authenticated() gate");
        assertFalse(agents.getBody().isEmpty(),
                "agent templates list must be non-empty (at least one built-in template is shipped)");

        ResponseEntity<List<Map<String, Object>>> teams = rest.exchange(
                url("/api/config/team-templates"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, teams.getStatusCode(),
                "GET /api/config/team-templates must return 200");
        assertFalse(teams.getBody().isEmpty(),
                "team templates list must be non-empty");

        ResponseEntity<List<Map<String, Object>>> workflows = rest.exchange(
                url("/api/config/workflow-templates"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, workflows.getStatusCode(),
                "GET /api/config/workflow-templates must return 200");
        assertFalse(workflows.getBody().isEmpty(),
                "workflow templates list must be non-empty");
    }

    // §2 (agm-left.md) — wave 3 T002. The bootstrap-payload shape contract: GET /api/config
    // returns exactly 7 top-level keys — adding a new key is an intentional gate (this test
    // must be bumped). The four list keys are present-and-empty (NOT null) under a fresh
    // truncated DB; the three string keys are non-blank. Version is profile-bound (resolves
    // from agentmanager.version=1.0.0 by default), so we assert non-blank only — not the
    // literal value.
    @Test
    void getConfigRootReturnsBootstrapPayloadShape() {
        String username = "config-shape-" + shortUuid();
        HttpHeaders auth = authenticateAs(username, username + "@test.local",
                "pass-cfg-shape-1234", List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/config"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/config must return 200 for an authenticated caller");
        assertNotNull(response.getBody(), "body must be non-null");
        Map<String, Object> body = response.getBody();

        Set<String> expected = Set.of("os_id", "name", "version",
                "agents", "teams", "workflows", "knowledge_bases");
        assertEquals(expected, body.keySet(),
                "bootstrap payload keys must exactly equal " + expected
                        + " — adding/removing a key is an intentional gate that requires bumping this test");

        assertInstanceOf(List.class, body.get("agents"),
                "'agents' must be a JSON array (empty list under truncated DB, never null)");
        assertInstanceOf(List.class, body.get("teams"),
                "'teams' must be a JSON array (empty list under truncated DB, never null)");
        assertInstanceOf(List.class, body.get("workflows"),
                "'workflows' must be a JSON array (empty list under truncated DB, never null)");
        assertInstanceOf(List.class, body.get("knowledge_bases"),
                "'knowledge_bases' must be a JSON array (empty list under truncated DB, never null)");

        assertInstanceOf(String.class, body.get("os_id"));
        assertFalse(((String) body.get("os_id")).isBlank(), "'os_id' must be a non-blank String");
        assertInstanceOf(String.class, body.get("name"));
        assertFalse(((String) body.get("name")).isBlank(), "'name' must be a non-blank String");
        assertInstanceOf(String.class, body.get("version"));
        assertFalse(((String) body.get("version")).isBlank(),
                "'version' must be a non-blank String (profile-bound; literal not asserted)");
    }

    // §2 (agm-left.md) — wave 3 T003. The bootstrap-payload's four list keys (`agents`,
    // `teams`, `workflows`, `knowledge_bases`) MUST be org-scoped. Any leak here means every
    // UI first-paint reveals cross-tenant data — undermining the wave-1..wave-5 tenant-
    // isolation initiative. Two admins in two orgs each seed one row per key in their own
    // org; each admin's GET /api/config returns exactly their own row, never the other org's.
    // Per-key assertions are mandatory (no inductive jumps from a single service).
    //
    // Note: `knowledge_bases` payload key is populated by `KnowledgeService.listFiles()`
    // which returns `KnowledgeContent` records (not `KnowledgeBase` rows) — fix-side filters
    // by KB→org_id chain. Seed via JDBC (production ingest path is heavy; KB row goes through
    // CRUD; content row goes through JDBC mirroring KnowledgeBaseTenantIsolationRuntimeTest's
    // pattern).
    @Test
    void getConfigRootIsOrgScopedAcrossAllFourListKeys() {
        HttpHeaders orgA = registerLoginWithOrg("cfg-iso-a", "cfg-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("cfg-iso-b", "cfg-iso-org-B");

        // Org B seeds one of each kind. Org A seeds none yet.
        seedAgentRowForOrg("cfg-iso-agent-b", "cfg-iso-org-B");
        createTeam(orgB, "B's Team");
        createWorkflow(orgB, "B's Workflow");
        UUID bKbId = UUID.fromString(createKb(orgB, "B's KB"));
        seedKnowledgeContent(UUID.randomUUID(), bKbId, "B's content");

        // Negative: org A's bootstrap payload sees zero of org B's seeds across all 4 keys.
        Map<String, Object> bodyA = getConfigBody(orgA);
        assertEquals(0, ((List<?>) bodyA.get("agents")).size(),
                "agents list must be empty for org A while org B has seeds; got " + bodyA.get("agents"));
        assertEquals(0, ((List<?>) bodyA.get("teams")).size(),
                "teams list must be empty for org A while org B has seeds; got " + bodyA.get("teams"));
        assertEquals(0, ((List<?>) bodyA.get("workflows")).size(),
                "workflows list must be empty for org A while org B has seeds; got " + bodyA.get("workflows"));
        assertEquals(0, ((List<?>) bodyA.get("knowledge_bases")).size(),
                "knowledge_bases list must be empty for org A while org B has seeds; got " + bodyA.get("knowledge_bases"));

        // Positive: now seed one of each in org A; org A's bootstrap payload returns
        // exactly one of each (its own row only — never org B's).
        seedAgentRowForOrg("cfg-iso-agent-a", "cfg-iso-org-A");
        createTeam(orgA, "A's Team");
        createWorkflow(orgA, "A's Workflow");
        UUID aKbId = UUID.fromString(createKb(orgA, "A's KB"));
        seedKnowledgeContent(UUID.randomUUID(), aKbId, "A's content");

        Map<String, Object> bodyA2 = getConfigBody(orgA);
        // `agents` key aggregates agents + teams as unified AgentDefinitions
        // (DatabaseAgentRegistry.findAll merges teamRepository.findByOrgId into the result),
        // so seeding 1 agent + 1 team produces 2 entries here. Both must be org A's.
        assertEquals(2, ((List<?>) bodyA2.get("agents")).size(),
                "agents list must contain org A's 1 agent + 1 team (unified definitions); got " + bodyA2.get("agents"));
        assertEquals(1, ((List<?>) bodyA2.get("teams")).size(),
                "teams list must contain exactly org A's 1 team; got " + bodyA2.get("teams"));
        assertEquals(1, ((List<?>) bodyA2.get("workflows")).size(),
                "workflows list must contain exactly org A's 1 workflow; got " + bodyA2.get("workflows"));
        assertEquals(1, ((List<?>) bodyA2.get("knowledge_bases")).size(),
                "knowledge_bases list must contain exactly org A's 1 content row; got " + bodyA2.get("knowledge_bases"));
    }

    // §2 (agm-left.md) — wave 3 T007. GET /api/config without an Authorization header
    // returns 4xx (default authenticated() gate; matches the documented behavior of
    // sibling /api/config/templates per `configTemplateEndpointsReturnBuiltInTemplates`).
    @Test
    void getConfigRootRequiresAuthentication() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/config"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertTrue(response.getStatusCode().is4xxClientError(),
                "GET /api/config with no Authorization header must return 4xx; got " + response.getStatusCode());
    }

    // Matrix §27.7 ideal — the spec text says "audit row records the erasure". Today the
    // ErasureRequest row in erasure_requests is the canonical audit artifact (id,
    // requested_by, started_at, completed_at, summary, status). No row is written into
    // agent_audits on erasure completion. Pinned @Disabled until a cross-cutting audit
    // hook on ErasureOrchestrationService emits an AgentAuditEntity with
    // action="ERASURE_COMPLETED" (or a new audit table grows up).
    @Test
    @Disabled("Matrix §27.7 ideal — erasure audit row emission. Today only erasure_requests captures the event; agent_audits is untouched by the orchestrator. Flip when a cross-cutting audit hook is added.")
    void erasureShouldEmitAuditRowOnCompletion() {
        // Intentionally empty until the audit hook lands.
    }

    // ─── helpers ───

    private String seedSession(String userId, LocalDateTime updatedAt) {
        String sessionId = "sess-" + shortUuid();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?)
                """, sessionId, userId, userId, updatedAt, updatedAt);
        return sessionId;
    }

    private String seedRun(String userId, LocalDateTime createdAt) {
        String runId = "run-" + shortUuid();
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status, input, output, created_at, updated_at)
                VALUES (?, NULL, NULL, ?, ?, 'COMPLETED', 'seed-input', 'seed-output', ?, ?)
                """, runId, userId, userId, createdAt, createdAt);
        return runId;
    }

    private UUID seedMemory(String userId) {
        UUID id = UUID.randomUUID();
        AgenticMemoryEntity mem = new AgenticMemoryEntity();
        mem.setMemoryId(id);
        mem.setUserId(userId);
        mem.setMemory("seeded memory for " + userId);
        mem.setMemoryTier(AgenticMemoryEntity.MemoryTier.USER_MEMORY);
        mem.setCreatedAt(LocalDateTime.now().minusHours(1));
        mem.setUpdatedAt(LocalDateTime.now().minusHours(1));
        memoryRepository.save(mem);
        return id;
    }

    /**
     * Seeds an agents row so FK-bound audit/other tables can reference it. Same pattern
     * as AuditLogsRuntimeTest#seedAgentRow. ON CONFLICT so the helper is idempotent.
     */
    private void seedAlertEvent(String id, String ruleId, boolean acknowledged, int daysAgo) {
        jdbc.update("""
                INSERT INTO alert_events (id, rule_id, metric_value, message, severity, acknowledged, fired_at)
                VALUES (?, ?, 1.0, 'retention seed', 'INFO', ?, ?)
                """, id, ruleId, acknowledged, java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(daysAgo)));
    }

    private int alertEventCount(String id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM alert_events WHERE id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    private void seedAlertRule(String ruleId) {
        jdbc.update("""
                INSERT INTO alert_rules (id, name, metric_name, condition, threshold, window_seconds, severity, enabled)
                VALUES (?, ?, 'metric.test', 'GT', 0.0, 60, 'INFO', true)
                ON CONFLICT (id) DO NOTHING
                """, ruleId, "T048 retention rule " + ruleId);
    }

    private void seedAgentRow(String agentId) {
        // First seed a model row (agents.model_id has no FK but production writers always
        // carry a model reference; keeping the test shape realistic).
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('fake-t048-model', 'fake-t048-model', 'fake', 'fake-t048-model', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'fake-t048-model', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, "T048 Test Agent " + agentId);
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ─── wave 3 (T003) cross-tenant fixture helpers ───

    private Map<String, Object> getConfigBody(HttpHeaders auth) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/config"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        return resp.getBody();
    }

    private String createTeam(HttpHeaders auth, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/teams"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name), auth), JSON_MAP);
        assertTrue(resp.getStatusCode().is2xxSuccessful(),
                "createTeam fixture must succeed; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name), auth), JSON_MAP);
        assertTrue(resp.getStatusCode().is2xxSuccessful(),
                "createWorkflow fixture must succeed; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private String createKb(HttpHeaders auth, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "description", "wave-3 fixture"), auth),
                JSON_MAP);
        assertTrue(resp.getStatusCode().is2xxSuccessful(),
                "createKb fixture must succeed; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private void seedAgentRowForOrg(String agentId, String orgId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('fake-wave3-model', 'fake-wave3-model', 'fake', 'fake-wave3-model', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'fake-wave3-model', true, ?, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, "wave3 agent " + agentId, orgId);
    }

    private void seedKnowledgeContent(UUID id, UUID kbId, String name) {
        jdbc.update("""
                INSERT INTO knowledge_contents
                  (id, name, description, content_type, uri, content_hash, size, status,
                   status_message, metadata, vector_ids, knowledge_base_id, owner_id, access_count,
                   created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::uuid, ?, ?, NOW(), NOW())
                """,
                id, name, "wave-3 fixture", "text/plain", "wave3://" + name,
                "hash-" + id, 16, "COMPLETED", "ok", "{}", new java.util.UUID[0], kbId,
                "wave3-owner", 0);
    }
}
