package ai.operativus.agentmanager.control.service.erasure;

import ai.operativus.agentmanager.control.repository.AgentAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditErasureHandler implements ErasureHandler {

    private final AgentAuditRepository auditRepository;
    private final JdbcTemplate jdbcTemplate;

    public AuditErasureHandler(AgentAuditRepository auditRepository, JdbcTemplate jdbcTemplate) {
        this.auditRepository = auditRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String domain() { return "auditLogs"; }

    @Override
    @Transactional
    public int erase(String userId) {
        var audits = auditRepository.findByUsername(userId);
        if (audits.isEmpty()) {
            return 0;
        }
        // agent_audits is append-only (trigger in changeset 029). GDPR-erasure redaction is
        // one of the two authorized mutation paths — set the bypass flag so the UPDATE passes.
        jdbcTemplate.execute("SET LOCAL agm.audit_immutability_bypass = 'true'");
        audits.forEach(a -> a.setUsername("[REDACTED]"));
        auditRepository.saveAll(audits);
        return audits.size();
    }
}
