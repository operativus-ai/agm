package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the {@code agent_audits} append-only invariant guaranteed by
 *   the {@code trg_agent_audits_immutable} PostgreSQL trigger (Liquibase changeset 029).
 *   The trigger raises {@code restrict_violation} on UPDATE/DELETE unless the session-local
 *   {@code agm.audit_immutability_bypass} flag is set to {@code 'true'}. That bypass is
 *   used by {@code DataRetentionService}, {@code ComplianceExportService}, and
 *   {@code AuditErasureHandler} for §24.4 administrative purge / GDPR redaction; all other
 *   callers must hit the rejection path.
 *
 *   Existing tests rely on the trigger working but no integration test asserts it
 *   directly. This canary closes that gap.
 *
 * Sibling: {@code a2a_task_events} carries a parallel trigger
 *   ({@code trg_a2a_task_events_immutable}, changeset 063) pinned by
 *   {@link A2aTaskEventsImmutabilityRuntimeTest}.
 *
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class AgentAuditsImmutabilityRuntimeTest extends BaseIntegrationTest {

    private String fixtureAgentId;
    private String fixtureAuditId;

    @BeforeEach
    void seedAgentAndAudit() {
        // Seed a model so the agents FK is satisfiable.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, "gpt-4o-mini", "gpt-4o-mini", "gpt-4o-mini");

        fixtureAgentId = "agent-imm-" + UUID.randomUUID();
        // Minimal agents row — only NOT NULL columns. The agent_audits FK requires
        // the agent row to exist; we don't care about agent semantics in this test.
        jdbc.update(
                "INSERT INTO agents (id, name, model_id, active) VALUES (?, ?, 'gpt-4o-mini', true)",
                fixtureAgentId, "imm-test");

        fixtureAuditId = "audit-imm-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_audits (id, agent_id, action, username, created_at)
                VALUES (?, ?, 'CREATE', 'test-user', now())
                """, fixtureAuditId, fixtureAgentId);
    }

    @Test
    void update_on_agent_audits_without_bypass_isRejectedBy_trg_agent_audits_immutable() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE agent_audits SET action = 'TAMPERED' WHERE id = ?", fixtureAuditId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        String actionAfter = jdbc.queryForObject(
                "SELECT action FROM agent_audits WHERE id = ?", String.class, fixtureAuditId);
        assertEquals("CREATE", actionAfter,
                "UPDATE must have been rolled back — row data unchanged after trigger rejection");
    }

    @Test
    void delete_on_agent_audits_without_bypass_isRejectedBy_trg_agent_audits_immutable() {
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM agent_audits WHERE id = ?", fixtureAuditId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE id = ?", Integer.class, fixtureAuditId);
        assertEquals(1, remaining, "DELETE must have been rejected — row still present");
    }

    @Test
    void update_with_bypass_flag_is_allowed_for_administrative_paths() {
        // Mirrors what DataRetentionService.legitimateRedact() does inside its
        // transactional scope: SET LOCAL the bypass + perform the UPDATE in the same
        // transaction. The flag is reset at txn end.
        jdbc.execute("BEGIN");
        try {
            jdbc.execute("SET LOCAL agm.audit_immutability_bypass = 'true'");
            int rows = jdbc.update(
                    "UPDATE agent_audits SET username = 'REDACTED' WHERE id = ?", fixtureAuditId);
            jdbc.execute("COMMIT");
            assertEquals(1, rows, "with bypass, UPDATE must succeed");
        } catch (RuntimeException e) {
            jdbc.execute("ROLLBACK");
            throw e;
        }

        String usernameAfter = jdbc.queryForObject(
                "SELECT username FROM agent_audits WHERE id = ?", String.class, fixtureAuditId);
        assertEquals("REDACTED", usernameAfter,
                "bypassed UPDATE must persist — DataRetentionService / AuditErasureHandler rely on this");
    }

    // The former a2a_task_events_KNOWN_GAP_isFreelyMutable_today canary lived here; it was
    // deleted when changeset 063 added trg_a2a_task_events_immutable. Coverage of the new
    // trigger lives in A2aTaskEventsImmutabilityRuntimeTest (same package).
}
