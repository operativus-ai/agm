package ai.operativus.agentmanager.integration.tasks;

import ai.operativus.agentmanager.control.repository.TaskRepository;
import ai.operativus.agentmanager.core.entity.TaskEntity;
import ai.operativus.agentmanager.core.entity.TaskStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins REQ-TT-1's cross-tenant isolation contract for
 *   {@link TaskRepository}. Every read query must AND-filter by {@code orgId};
 *   the {@code atomicallyDispatch} mutating CAS must refuse to flip a row that
 *   belongs to a different tenant even when the caller knows the task id.
 *
 *   <p>Cases:
 *   <ol>
 *     <li>{@code findByIdAndOrgId} — same org returns row, different org returns empty.</li>
 *     <li>{@code findByTeamRunIdAndOrgIdOrderByCreatedAtAsc} — only same-org rows.</li>
 *     <li>{@code atomicallyDispatch} — same org + PENDING flips to IN_PROGRESS; cross-org
 *         caller cannot flip even with the right task id; second call by same org
 *         returns 0 (idempotency).</li>
 *     <li>{@code countByTeamRunIdAndStatusIn} — counts only the rows for the requested
 *         team-run id, regardless of org (the team-run id is itself tenant-scoped at
 *         the parent {@code agent_runs.org_id} layer).</li>
 *   </ol>
 *
 * State: Stateless test fixture (each test seeds its own rows).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class TaskRepositoryTenantIsolationRuntimeTest extends BaseIntegrationTest {

    @Autowired private TaskRepository tasks;

    private static final String ORG_A = "tt1-org-a";
    private static final String ORG_B = "tt1-org-b";

    @Test
    void findByIdAndOrgId_returnsRow_onlyForOwningOrg() {
        String runIdA = seedRun(ORG_A);
        TaskEntity t = saveTask(runIdA, ORG_A, "alpha", TaskStatus.PENDING);

        assertThat(tasks.findByIdAndOrgId(t.getId(), ORG_A)).isPresent();
        assertThat(tasks.findByIdAndOrgId(t.getId(), ORG_B))
                .as("Cross-tenant fetch must return empty — task id alone is not authorization")
                .isEmpty();
    }

    @Test
    void findByTeamRunIdAndOrgId_returnsOnlyOwningOrgsRows() {
        String runIdA = seedRun(ORG_A);
        String runIdB = seedRun(ORG_B);
        saveTask(runIdA, ORG_A, "task-a1", TaskStatus.PENDING);
        saveTask(runIdA, ORG_A, "task-a2", TaskStatus.PENDING);
        saveTask(runIdB, ORG_B, "task-b1", TaskStatus.PENDING);

        List<TaskEntity> aRows = tasks.findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(runIdA, ORG_A);
        assertThat(aRows).extracting(TaskEntity::getOrgId).containsOnly(ORG_A);
        assertThat(aRows).extracting(TaskEntity::getTitle).containsExactly("task-a1", "task-a2");

        // Same teamRunId but wrong org → no rows.
        assertThat(tasks.findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(runIdA, ORG_B)).isEmpty();
    }

    @Test
    void atomicallyDispatch_flipsPendingToInProgress_andIsIdempotent() {
        String runIdA = seedRun(ORG_A);
        TaskEntity t = saveTask(runIdA, ORG_A, "race-target", TaskStatus.PENDING);

        int first = tasks.atomicallyDispatch(t.getId(), ORG_A, "worker-1", LocalDateTime.now());
        assertThat(first).as("First caller wins the CAS").isEqualTo(1);

        int second = tasks.atomicallyDispatch(t.getId(), ORG_A, "worker-2", LocalDateTime.now());
        assertThat(second).as("Second caller observes IN_PROGRESS and gets 0 affected rows")
                .isZero();

        TaskEntity reloaded = tasks.findByIdAndOrgId(t.getId(), ORG_A).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-1");
        assertThat(reloaded.getDispatchedAt()).isNotNull();
    }

    @Test
    void atomicallyDispatch_refusesCrossTenantCaller() {
        String runIdA = seedRun(ORG_A);
        TaskEntity t = saveTask(runIdA, ORG_A, "tenant-locked", TaskStatus.PENDING);

        int crossOrg = tasks.atomicallyDispatch(t.getId(), ORG_B, "attacker", LocalDateTime.now());
        assertThat(crossOrg).as("Wrong-org dispatch must affect zero rows").isZero();

        TaskEntity reloaded = tasks.findByIdAndOrgId(t.getId(), ORG_A).orElseThrow();
        assertThat(reloaded.getStatus()).as("Row must remain PENDING after cross-tenant attempt")
                .isEqualTo(TaskStatus.PENDING);
        assertThat(reloaded.getWorkerId()).isNull();
    }

    @Test
    void countByTeamRunIdAndStatusIn_groupsCorrectly() {
        String runIdA = seedRun(ORG_A);
        saveTask(runIdA, ORG_A, "t1", TaskStatus.COMPLETED);
        saveTask(runIdA, ORG_A, "t2", TaskStatus.FAILED);
        saveTask(runIdA, ORG_A, "t3", TaskStatus.PENDING);

        long terminal = tasks.countByTeamRunIdAndStatusIn(runIdA,
                List.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.BLOCKED));
        assertThat(terminal).isEqualTo(2);
    }

    // --- helpers ---

    private String seedRun(String orgId) {
        String agentId = "tt1-agent-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at, is_team)
                VALUES (?, 'tt1 fixture', 'task-repo test', 'noop', NULL, true,
                        1, 'TIER_1_STANDARD', false, 0, ?, now(), now(), false)
                """, agentId, orgId);
        String sessionId = "tt1-session-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, title)
                VALUES (?, 'tt1', ?, ?, 'tt1 fixture')
                """, sessionId, orgId, agentId);
        String runId = "tt1-run-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status,
                                        created_at, updated_at, created_by, updated_by, version)
                VALUES (?, ?, ?, 'tt1', ?, 'COMPLETED',
                        now(), now(), 'tt1', 'tt1', 0)
                """, runId, agentId, sessionId, orgId);
        return runId;
    }

    private TaskEntity saveTask(String teamRunId, String orgId, String title, TaskStatus status) {
        TaskEntity t = new TaskEntity();
        t.setId("tt1-task-" + UUID.randomUUID().toString().substring(0, 8));
        t.setTeamRunId(teamRunId);
        t.setOrgId(orgId);
        t.setTitle(title);
        t.setStatus(status);
        t.setDependencies(new String[0]);
        return tasks.save(t);
    }
}
