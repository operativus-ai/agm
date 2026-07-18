package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.*;
import ai.operativus.agentmanager.core.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GDPR Article 20 — Right to Data Portability.
 * Exports all user-attributable data as a structured JSON document.
 * Covers: sessions, runs, memories, approvals, and audit logs.
 */
@Service
@Transactional(readOnly = true)
public class ComplianceExportService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceExportService.class);

    private final SessionRepository sessionRepository;
    private final RunRepository runRepository;
    private final AgenticMemoryRepository memoryRepository;
    private final ApprovalRepository approvalRepository;
    private final AgentAuditRepository auditRepository;
    private final JdbcTemplate jdbcTemplate;

    public ComplianceExportService(
            SessionRepository sessionRepository,
            RunRepository runRepository,
            AgenticMemoryRepository memoryRepository,
            ApprovalRepository approvalRepository,
            AgentAuditRepository auditRepository,
            JdbcTemplate jdbcTemplate) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.memoryRepository = memoryRepository;
        this.approvalRepository = approvalRepository;
        this.auditRepository = auditRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Exports all data attributable to the given userId as a portable JSON map.
     * Suitable for GDPR Article 20 (data portability) and Article 15 (right of access).
     */
    public Map<String, Object> exportUserData(String userId) {
        log.info("GDPR data export initiated for userId={}", userId);

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportType", "GDPR_DATA_PORTABILITY");
        export.put("userId", userId);
        export.put("exportedAt", java.time.LocalDateTime.now().toString());

        // Sessions
        var sessions = sessionRepository.findAll().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .toList();
        export.put("sessions", sessions);

        // Runs
        var runs = runRepository.findByUserId(userId);
        export.put("runs", runs);

        // Memories
        var memories = memoryRepository.findByUserId(userId);
        export.put("memories", memories);

        // Approvals requested by user
        var approvals = approvalRepository.findByRequestedBy(userId);
        export.put("approvals", approvals);

        // Audit actions by user
        var audits = auditRepository.findByUsername(userId);
        export.put("auditLogs", audits);

        log.info("GDPR data export completed for userId={}. Sessions={}, Runs={}, Memories={}, Approvals={}, Audits={}",
                userId, sessions.size(), runs.size(), memories.size(), approvals.size(), audits.size());

        return export;
    }

    /**
     * GDPR Article 17 — Right to Erasure.
     * Deletes all user-attributable data. Audit logs are anonymized (username set to "[REDACTED]") rather than deleted.
     */
    @Transactional
    public Map<String, Integer> eraseUserData(String userId) {
        log.warn("GDPR data erasure initiated for userId={}", userId);

        Map<String, Integer> counts = new LinkedHashMap<>();

        // Delete memories
        var memories = memoryRepository.findByUserId(userId);
        memoryRepository.deleteAll(memories);
        counts.put("memoriesDeleted", memories.size());

        // Delete runs
        var runs = runRepository.findByUserId(userId);
        runRepository.deleteAll(runs);
        counts.put("runsDeleted", runs.size());

        // Delete sessions
        var sessions = sessionRepository.findAll().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .toList();
        sessionRepository.deleteAll(sessions);
        counts.put("sessionsDeleted", sessions.size());

        // Anonymize audit logs (preserve for compliance, redact identity). agent_audits is
        // append-only (trg_agent_audits_immutable, changeset 029); set the bypass flag on
        // this transaction so the UPDATE passes.
        var audits = auditRepository.findByUsername(userId);
        if (!audits.isEmpty()) {
            jdbcTemplate.execute("SET LOCAL agm.audit_immutability_bypass = 'true'");
            audits.forEach(a -> a.setUsername("[REDACTED]"));
            auditRepository.saveAll(audits);
        }
        counts.put("auditLogsAnonymized", audits.size());

        log.warn("GDPR data erasure completed for userId={}. Counts: {}", userId, counts);
        return counts;
    }
}
