package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.*;
import com.operativus.agentmanager.control.repository.GlobalSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Automated data retention policy enforcement.
 * Purges old sessions, runs, audit logs, and alert events based on configurable retention periods.
 * Runs daily at 3:00 AM.
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    @Value("${app.retention.sessions-days:90}")
    private int sessionRetentionDays;

    @Value("${app.retention.runs-days:180}")
    private int runRetentionDays;

    @Value("${app.retention.audit-days:365}")
    private int auditRetentionDays;

    @Value("${app.retention.alerts-days:30}")
    private int alertRetentionDays;

    private final SessionRepository sessionRepository;
    private final RunRepository runRepository;
    private final AgentAuditRepository auditRepository;
    private final AlertEventRepository alertEventRepository;
    private final GlobalSettingRepository globalSettingRepository;
    private final JdbcTemplate jdbcTemplate;

    public DataRetentionService(
            SessionRepository sessionRepository,
            RunRepository runRepository,
            AgentAuditRepository auditRepository,
            AlertEventRepository alertEventRepository,
            GlobalSettingRepository globalSettingRepository,
            JdbcTemplate jdbcTemplate) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.auditRepository = auditRepository;
        this.alertEventRepository = alertEventRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes data retention policies daily at 3 AM.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public Map<String, Integer> enforceRetentionPolicies() {
        int effectiveSessionDays = readRetentionDays(SettingsService.KEY_RETENTION_SESSIONS_DAYS, sessionRetentionDays);
        int effectiveRunDays = readRetentionDays(SettingsService.KEY_RETENTION_RUNS_DAYS, runRetentionDays);
        int effectiveAuditDays = readRetentionDays(SettingsService.KEY_RETENTION_AUDIT_DAYS, auditRetentionDays);
        int effectiveAlertDays = readRetentionDays(SettingsService.KEY_RETENTION_ALERTS_DAYS, alertRetentionDays);

        log.info("Data retention enforcement started. Policies: sessions={}d, runs={}d, audit={}d, alerts={}d",
                effectiveSessionDays, effectiveRunDays, effectiveAuditDays, effectiveAlertDays);

        Map<String, Integer> purged = new LinkedHashMap<>();

        // Purge old sessions
        LocalDateTime sessionCutoff = LocalDateTime.now().minusDays(effectiveSessionDays);
        var oldSessions = sessionRepository.findAll().stream()
                .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().isBefore(sessionCutoff))
                .toList();
        if (!oldSessions.isEmpty()) {
            sessionRepository.deleteAll(oldSessions);
        }
        purged.put("sessions", oldSessions.size());

        // Purge old alert events (acknowledged only)
        var oldAlerts = alertEventRepository.findAll().stream()
                .filter(a -> a.isAcknowledged() && a.getFiredAt().isBefore(LocalDateTime.now().minusDays(effectiveAlertDays)))
                .toList();
        if (!oldAlerts.isEmpty()) {
            alertEventRepository.deleteAll(oldAlerts);
        }
        purged.put("alertEvents", oldAlerts.size());

        // Purge old agent runs. Order matters: reflection children first (fk_agent_reflections_run),
        // then nullify self-referencing parent_run_id pointers into the deletion set, then the runs
        // themselves. The summary count reports runs only — reflections follow implicitly.
        LocalDateTime runCutoff = LocalDateTime.now().minusDays(effectiveRunDays);
        runRepository.purgeReflectionsOfRunsOlderThan(runCutoff);
        runRepository.nullifyParentRefsToRunsOlderThan(runCutoff);
        int purgedRuns = runRepository.deleteByCreatedAtBefore(runCutoff);
        purged.put("runs", purgedRuns);

        // Purge old audit logs. agent_audits is append-only (trigger in changeset 029):
        // set the session-local bypass flag inside this transaction so the retention
        // DELETE is allowed to pass. SET LOCAL dies with the transaction.
        LocalDateTime auditCutoff = LocalDateTime.now().minusDays(effectiveAuditDays);
        jdbcTemplate.execute("SET LOCAL agm.audit_immutability_bypass = 'true'");
        int purgedAudits = auditRepository.deleteByCreatedAtBefore(auditCutoff);
        purged.put("agentAudits", purgedAudits);

        // AGM logging plan §5.12/§5.14: agent_run_events and orchestration_decisions are
        // append-only timeline tables. Both share the audit-days window so forensic retention
        // aligns with the rest of the compliance audit surface.
        int purgedRunEvents = jdbcTemplate.update(
                "DELETE FROM agent_run_events WHERE event_ts < ?", auditCutoff);
        purged.put("agentRunEvents", purgedRunEvents);

        int purgedOrchDecisions = jdbcTemplate.update(
                "DELETE FROM orchestration_decisions WHERE created_at < ?", auditCutoff);
        purged.put("orchestrationDecisions", purgedOrchDecisions);

        // OBS-T005: GC past-expiry SSE token rows. Idempotent if the table doesn't exist
        // (older Postgres deployments) — wrap defensively. Caffeine and Redis impls self-expire,
        // so this only matters when agm.sse.token-store=postgres.
        try {
            int purgedSseTokens = jdbcTemplate.update(
                    "DELETE FROM sse_tokens WHERE expires_at < NOW()");
            purged.put("sseTokens", purgedSseTokens);
        } catch (RuntimeException ex) {
            log.debug("sse_tokens cleanup skipped: {}", ex.getMessage());
        }

        log.info("Data retention enforcement completed. Purged: {}", purged);
        return purged;
    }

    private int readRetentionDays(String key, int fallback) {
        try {
            return globalSettingRepository.findById(key)
                    .map(s -> {
                        try {
                            return Integer.parseInt(s.getValue());
                        } catch (NumberFormatException e) {
                            log.warn("Non-numeric retention setting for key '{}': '{}' — using fallback {}d", key, s.getValue(), fallback);
                            return fallback;
                        }
                    })
                    .orElse(fallback);
        } catch (Exception e) {
            log.warn("Failed to read retention setting '{}' from DB — using fallback {}d", key, fallback);
            return fallback;
        }
    }

    /**
     * Returns the current retention policy configuration.
     */
    public Map<String, Integer> getRetentionPolicies() {
        Map<String, Integer> policies = new LinkedHashMap<>();
        policies.put("sessions_days", readRetentionDays(SettingsService.KEY_RETENTION_SESSIONS_DAYS, sessionRetentionDays));
        policies.put("runs_days", readRetentionDays(SettingsService.KEY_RETENTION_RUNS_DAYS, runRetentionDays));
        policies.put("audit_days", readRetentionDays(SettingsService.KEY_RETENTION_AUDIT_DAYS, auditRetentionDays));
        policies.put("alerts_days", readRetentionDays(SettingsService.KEY_RETENTION_ALERTS_DAYS, alertRetentionDays));
        return policies;
    }
}
