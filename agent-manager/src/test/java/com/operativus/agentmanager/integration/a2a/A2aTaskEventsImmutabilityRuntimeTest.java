package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the {@code a2a_task_events} append-only invariant guaranteed
 *   by the {@code trg_a2a_task_events_immutable} PostgreSQL trigger (Liquibase changeset
 *   063). The trigger raises {@code restrict_violation} on UPDATE/DELETE unconditionally —
 *   unlike the sibling {@code agent_audits} trigger (changeset 029), there is no bypass
 *   flag today because no production path legitimately mutates these rows. Add a
 *   {@code current_setting('agm.a2a_task_events_immutability_bypass', true)} branch in
 *   {@code a2a_task_events_reject_mutation()} when a redaction/retention path lands.
 *
 *   The {@code A2aTaskEventRepository} is read+save only by contract; this canary protects
 *   against schema drift (someone adding a {@code @Modifying} delete query or a raw SQL
 *   purge) by enforcing the invariant at the DB layer.
 *
 * Sibling: {@link AgentAuditsImmutabilityRuntimeTest} pins the analogous trigger on
 *   {@code agent_audits}, including the bypass flag branch that the present table lacks.
 *
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aTaskEventsImmutabilityRuntimeTest extends BaseIntegrationTest {

    private String fixtureTaskId;

    @BeforeEach
    void seedEventRow() {
        // a2a_task_events.target_agent_id no longer carries a FK (dropped in changeset
        // 027-a2a-task-events-drop-agent-fk) — we can insert without seeding agents.
        fixtureTaskId = "task-imm-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO a2a_task_events (task_id, target_agent_id, status, message, event_ts)
                VALUES (?, 'imm-test-agent', 'SUBMITTED', 'fixture event', now())
                """, fixtureTaskId);
    }

    @Test
    void update_on_a2a_task_events_isRejectedBy_trg_a2a_task_events_immutable() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE a2a_task_events SET message = 'TAMPERED' WHERE task_id = ?", fixtureTaskId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        String messageAfter = jdbc.queryForObject(
                "SELECT message FROM a2a_task_events WHERE task_id = ?",
                String.class, fixtureTaskId);
        assertEquals("fixture event", messageAfter,
                "UPDATE must have been rolled back — row data unchanged after trigger rejection. "
                        + "If this assertion fails, the BEFORE UPDATE branch of "
                        + "trg_a2a_task_events_immutable was lost (changeset 063 reverted?) or "
                        + "the function was redefined without RAISE EXCEPTION on TG_OP = 'UPDATE'");
    }

    @Test
    void delete_on_a2a_task_events_isRejectedBy_trg_a2a_task_events_immutable() {
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM a2a_task_events WHERE task_id = ?", fixtureTaskId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?",
                Integer.class, fixtureTaskId);
        assertEquals(1, remaining,
                "DELETE must have been rejected — row still present. A zero here would mean "
                        + "the trigger was dropped or the BEFORE DELETE branch was removed");
    }

    @Test
    void insertOnEvent_isAllowed_triggerIsScopedToMutations() {
        // Forward guard: the trigger must NOT fire on INSERT, otherwise the entire A2A
        // lifecycle persistence path is broken. This test inserts a sibling row alongside
        // the @BeforeEach fixture and asserts both are present.
        String siblingTaskId = "task-imm-insert-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO a2a_task_events (task_id, target_agent_id, status, message, event_ts)
                VALUES (?, 'imm-test-agent', 'WORKING', 'sibling insert', now())
                """, siblingTaskId);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id IN (?, ?)",
                Integer.class, fixtureTaskId, siblingTaskId);
        assertEquals(2, count,
                "INSERT must succeed — trg_a2a_task_events_immutable is BEFORE UPDATE OR DELETE, "
                        + "not BEFORE INSERT. If this fails, the trigger event clause picked up "
                        + "INSERT by mistake and every A2ATaskExecutor.recordEvent() call is broken");
    }
}
