package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.TasksOrchestrator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.TaskRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.TaskEntity;
import com.operativus.agentmanager.core.entity.TaskStatus;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.RecordingAgentOperations;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Direct runtime coverage of {@link TasksOrchestrator}'s skeleton —
 *   coordinator-runs-then-drain-worker-loop semantics. Tools land in REQ-TT-3, so the
 *   coordinator can't yet enqueue tasks; this test seeds the repository directly to
 *   exercise the drain loop in isolation.
 *
 *   <p>Cases:
 *   <ol>
 *     <li><b>Coordinator-only path</b> — no pre-seeded tasks, TASKS-mode team runs the
 *         coordinator once and returns its content. Worker loop exits immediately on
 *         empty queue.</li>
 *     <li><b>Worker loop drains pending tasks</b> — three independent tasks seeded
 *         {@link TaskStatus#PENDING} all transition to {@link TaskStatus#COMPLETED},
 *         each dispatched exactly once to its assignee.</li>
 *     <li><b>Cascade BLOCKED on FAILED dep</b> — pre-seed a FAILED task and a dependent
 *         PENDING one; dependent must end {@link TaskStatus#BLOCKED} without dispatching.</li>
 *   </ol>
 *
 * State: Stateless test fixture (per-test isolation via BaseIntegrationTest).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class TasksOrchestratorRuntimeTest extends BaseIntegrationTest {

    private static final String ORG = TenantConstants.DEFAULT_SYSTEM_ORG;

    @Autowired private TasksOrchestrator tasks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private AgentRegistry agentRegistry;
    @Autowired private MeterRegistry meterRegistry;

    private RecordingAgentOperations runner;

    @BeforeEach
    void resetHarness() {
        runner = new RecordingAgentOperations();
    }

    @Test
    void tasksMode_emptyQueue_runsCoordinatorOnlyAndReturnsContent() {
        String coordId = persistAgent("tt2-coord-" + UUID.randomUUID(), "Coordinator", true, true, "TASKS");
        AgentDefinition coordDef = agentRegistry.findById(coordId, ORG);
        assertThat(coordDef).isNotNull();

        runner.scriptResponse("coordinator-said-go");

        String teamRunId = seedRun(coordId);
        String output = ScopedValue.where(AgentContextHolder.currentRunId, teamRunId)
                .where(AgentContextHolder.orgId, ORG)
                .where(AgentContextHolder.sessionId, "sess-tt2")
                .call(() -> tasks.execute(coordDef, "kick off", null,
                        "sess-tt2", "user-tt2", ORG, false, runner));

        assertThat(output).isEqualTo("coordinator-said-go");
        assertThat(runner.calls).hasSize(1);
        assertThat(runner.calls.get(0).agentId()).isEqualTo(coordId);
        assertThat(taskRepository.findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(teamRunId, ORG)).isEmpty();
    }

    @Test
    void tasksMode_workerLoopDrainsPendingTasks() {
        String coordId = persistAgent("tt2-coord-" + UUID.randomUUID(), "Coordinator", true, true, "TASKS");
        String workerA = persistAgent("tt2-wa-" + UUID.randomUUID(), "Worker A", true, false, null);
        String workerB = persistAgent("tt2-wb-" + UUID.randomUUID(), "Worker B", true, false, null);
        AgentDefinition coordDef = agentRegistry.findById(coordId, ORG);

        String teamRunId = seedRun(coordId);
        seedTask(teamRunId, "task-1", workerA, TaskStatus.PENDING, new String[0]);
        seedTask(teamRunId, "task-2", workerB, TaskStatus.PENDING, new String[0]);
        seedTask(teamRunId, "task-3", workerA, TaskStatus.PENDING, new String[0]);

        runner.scriptResponse("coord-done");      // kickoff
        runner.scriptResponse("a-result-1");      // task 1 -> worker A
        runner.scriptResponse("b-result");        // task 2 -> worker B
        runner.scriptResponse("a-result-2");      // task 3 -> worker A
        runner.scriptResponse("synthesis-final"); // REQ-TT-7b synthesis pass

        String output = ScopedValue.where(AgentContextHolder.currentRunId, teamRunId)
                .where(AgentContextHolder.orgId, ORG)
                .where(AgentContextHolder.sessionId, "sess-tt2")
                .call(() -> tasks.execute(coordDef, "kick off", null,
                        "sess-tt2", "user-tt2", ORG, false, runner));

        // REQ-TT-7b — output is the synthesis pass, not the kickoff.
        assertThat(output).isEqualTo("synthesis-final");
        // 1 coordinator kickoff + 3 worker dispatches + 1 synthesis = 5.
        assertThat(runner.calls).hasSize(5);
        assertThat(runner.calls.get(0).agentId()).isEqualTo(coordId);
        assertThat(runner.calls.get(4).agentId())
                .as("Synthesis pass MUST target the coordinator").isEqualTo(coordId);

        List<TaskEntity> finalStates =
                taskRepository.findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(teamRunId, ORG);
        assertThat(finalStates).extracting(TaskEntity::getStatus)
                .containsOnly(TaskStatus.COMPLETED);
        assertThat(finalStates).extracting(TaskEntity::getResult)
                .containsExactlyInAnyOrder("a-result-1", "b-result", "a-result-2");
        assertThat(finalStates).extracting(TaskEntity::getDispatchedAt).allMatch(ts -> ts != null);

        // REQ-TT-4: each task fires at least 2 TASK_UPDATED events on the run timeline —
        // one on PENDING->IN_PROGRESS dispatch, one on IN_PROGRESS->COMPLETED.
        // Wait briefly for the async persistence executor to flush.
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .until(() -> countEvents(teamRunId, "TASK_UPDATED") >= 6);
        assertThat(countEvents(teamRunId, "TASK_UPDATED")).isGreaterThanOrEqualTo(6);

        // REQ-TT-5: 3 completions tagged by org+assignee. 2 distinct assignees → 2 counters.
        double completedA = meterRegistry.counter("agm.team.tasks.completed",
                "org", ORG, "assignee", workerA).count();
        double completedB = meterRegistry.counter("agm.team.tasks.completed",
                "org", ORG, "assignee", workerB).count();
        assertThat(completedA).isEqualTo(2.0);
        assertThat(completedB).isEqualTo(1.0);
        // Duration timer recorded once per completion. Timer is tagged by assignee+outcome,
        // so iterate across all matching meters.
        long totalDurationSamples = meterRegistry.find("agm.team.tasks.duration").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count)
                .sum();
        assertThat(totalDurationSamples).isGreaterThanOrEqualTo(3);
    }

    @Test
    void tasksMode_failedDependencyCascadesBlocked() {
        String coordId = persistAgent("tt2-coord-" + UUID.randomUUID(), "Coordinator", true, true, "TASKS");
        String worker  = persistAgent("tt2-w-" + UUID.randomUUID(), "Worker", true, false, null);
        AgentDefinition coordDef = agentRegistry.findById(coordId, ORG);

        String teamRunId = seedRun(coordId);
        String failedTaskId = seedTask(teamRunId, "upstream-failed", worker, TaskStatus.FAILED, new String[0]);
        seedTask(teamRunId, "downstream-pending", worker, TaskStatus.PENDING, new String[]{failedTaskId});

        runner.scriptResponse("coord-done");       // kickoff
        runner.scriptResponse("synth-after-block"); // synthesis (tasks exist even though none dispatched)
        // No worker dispatches scripted — none should happen.

        ScopedValue.where(AgentContextHolder.currentRunId, teamRunId)
                .where(AgentContextHolder.orgId, ORG)
                .where(AgentContextHolder.sessionId, "sess-tt2")
                .run(() -> tasks.execute(coordDef, "kick off", null,
                        "sess-tt2", "user-tt2", ORG, false, runner));

        // Kickoff + synthesis = 2 coordinator calls; zero worker dispatches (FAILED dep gates dispatch).
        assertThat(runner.calls).hasSize(2).extracting(c -> c.agentId()).containsOnly(coordId);
        List<TaskEntity> states =
                taskRepository.findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(teamRunId, ORG);
        assertThat(states).hasSize(2);
        TaskEntity downstream = states.stream()
                .filter(t -> "downstream-pending".equals(t.getTitle()))
                .findFirst().orElseThrow();
        assertThat(downstream.getStatus()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(downstream.getResult()).contains("Dependency").contains("FAILED");
    }

    // --- helpers ---

    private String persistAgent(String id, String name, boolean active, boolean isTeam, String teamMode) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(ORG);
        a.setName(name);
        a.setDescription("TT2 fixture: " + name);
        a.setInstructions("TT2 fixture instructions");
        a.setModelId(null);
        a.setActive(active);
        a.setMaintenanceMode(false);
        a.setTeam(isTeam);
        a.setTeamMode(teamMode);
        a.setMembers(List.of());
        a.setCapabilities(new String[0]);
        return agentRepository.save(a).getId();
    }

    private String seedRun(String agentId) {
        String sessionId = "tt2-sess-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, title)
                VALUES (?, 'tt2', ?, ?, 'tt2 fixture')
                """, sessionId, ORG, agentId);
        String runId = "tt2-run-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status,
                                        created_at, updated_at, created_by, updated_by, version)
                VALUES (?, ?, ?, 'tt2', ?, 'RUNNING',
                        now(), now(), 'tt2', 'tt2', 0)
                """, runId, agentId, sessionId, ORG);
        return runId;
    }

    private int countEvents(String teamRunId, String eventType) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = ?",
                Integer.class, teamRunId, eventType);
        return n == null ? 0 : n;
    }

    private String seedTask(String teamRunId, String title, String assigneeId,
                            TaskStatus status, String[] deps) {
        TaskEntity t = new TaskEntity();
        t.setId("tt2-task-" + UUID.randomUUID().toString().substring(0, 8));
        t.setTeamRunId(teamRunId);
        t.setOrgId(ORG);
        t.setTitle(title);
        t.setAssigneeAgentId(assigneeId);
        t.setStatus(status);
        t.setDependencies(deps);
        return taskRepository.save(t).getId();
    }
}
