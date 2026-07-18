package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.TaskRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.TaskEntity;
import com.operativus.agentmanager.core.entity.TaskStatus;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link TaskManagementTool}. Pinned cases:
 * <ul>
 *   <li><b>D9 unknown-assignee returns error string, not throw.</b></li>
 *   <li>createTask wires teamRunId from {@link AgentContextHolder#getCurrentRunId()} and
 *       orgId from {@link AgentContextHolder#getOrgId()}.</li>
 *   <li>updateTaskStatus rejects unknown status with an error string.</li>
 *   <li>queryTasks filters by status + assignee.</li>
 *   <li>getTask refuses a task from a different team-run even when org matches
 *       (defense-in-depth check).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TaskManagementToolTest {

    @Mock private TaskRepository tasks;
    @Mock private AgentRepository agents;
    @Mock private AgentRunEventBus eventBus;
    @Mock private AgentOperations runner;
    @Mock private ObjectProvider<AgentOperations> runnerProvider;

    private final MeterRegistry meters = new SimpleMeterRegistry();

    private static final String TEAM_RUN = "tm-run-1";
    private static final String ORG = "tm-org-1";

    private TaskManagementTool tool() {
        return new TaskManagementTool(tasks, agents, eventBus, meters, runnerProvider);
    }

    /** Tests that exercise the synchronous-dispatch path scope this stub in
     *  themselves so Mockito's strict-stubbing doesn't flag the unused stub on
     *  validation-fail-fast cases. */
    private void wireRunner() {
        when(runnerProvider.getObject()).thenReturn(runner);
    }

    private static RunResponse okResponse(String content) {
        return new RunResponse("run-id", "sess-id", content,
                new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
    }

    @Test
    void createTask_unknownAssignee_returnsErrorString_doesNotPersist() {
        when(agents.existsByIdAndOrgId("hallucinated", ORG)).thenReturn(false);
        TaskManagementTool t = tool();

        String result = withContext(() ->
                t.createTask("title", "desc", "hallucinated", List.of()));

        assertThat(result).startsWith("ERROR:").contains("hallucinated");
        verify(tasks, never()).save(any());
    }

    @Test
    void createTask_validAssignee_synchronouslyDispatchesAndReturnsContent() {
        when(agents.existsByIdAndOrgId("agent-x", ORG)).thenReturn(true);
        when(tasks.save(any(TaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // Atomic dispatch CAS wins.
        when(tasks.atomicallyDispatch(any(), any(), any(), any())).thenReturn(1);
        when(tasks.findByIdAndOrgId(any(), any()))
                .thenAnswer(inv -> Optional.of(entity(inv.getArgument(0), TEAM_RUN, ORG, TaskStatus.IN_PROGRESS, "agent-x")));
        wireRunner();
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(okResponse("assignee output text"));

        TaskManagementTool t = tool();
        String result = withContext(() ->
                t.createTask("plan things", "context here", "agent-x", List.of("dep-1", "dep-2")));

        // Returns assignee content, not the task id.
        assertThat(result).isEqualTo("assignee output text");

        // PENDING insert was captured.
        ArgumentCaptor<TaskEntity> persistCaptor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(tasks, org.mockito.Mockito.atLeastOnce()).save(persistCaptor.capture());
        TaskEntity firstSave = persistCaptor.getAllValues().get(0);
        assertThat(firstSave.getTeamRunId()).isEqualTo(TEAM_RUN);
        assertThat(firstSave.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(firstSave.getDependencies()).containsExactly("dep-1", "dep-2");

        // Runner was called with the description as the prompt.
        verify(runner).run(eq("agent-x"), eq("context here"), any(), any(), any(), eq(ORG), any(), any());

        // Created + Updated events emitted.
        ArgumentCaptor<AgentRunEvent> evt = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, org.mockito.Mockito.atLeast(2)).publish(evt.capture());
        assertThat(evt.getAllValues()).extracting(AgentRunEvent::eventType)
                .contains(AgentRunEventType.TASK_CREATED, AgentRunEventType.TASK_UPDATED);

        // Metrics: created + completed counters incremented.
        assertThat(meters.counter("agm.team.tasks.created", "org", ORG, "assignee", "agent-x").count())
                .isEqualTo(1.0);
        assertThat(meters.counter("agm.team.tasks.completed", "org", ORG, "assignee", "agent-x").count())
                .isEqualTo(1.0);
    }

    @Test
    void createTask_assigneeThrows_returnsErrorString_andCounterIncrements() {
        when(agents.existsByIdAndOrgId("agent-x", ORG)).thenReturn(true);
        when(tasks.save(any(TaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tasks.atomicallyDispatch(any(), any(), any(), any())).thenReturn(1);
        when(tasks.findByIdAndOrgId(any(), any()))
                .thenAnswer(inv -> Optional.of(entity(inv.getArgument(0), TEAM_RUN, ORG, TaskStatus.IN_PROGRESS, "agent-x")));
        wireRunner();
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("assignee blew up"));

        TaskManagementTool t = tool();
        String result = withContext(() ->
                t.createTask("plan things", "context here", "agent-x", null));

        assertThat(result).startsWith("ERROR:").contains("assignee blew up");
        assertThat(meters.counter("agm.team.tasks.failed", "org", ORG, "assignee", "agent-x").count())
                .isEqualTo(1.0);
    }

    @Test
    void createTask_blankTitle_returnsError() {
        TaskManagementTool t = tool();
        assertThat(withContext(() -> t.createTask("  ", "desc", "agent-x", null)))
                .startsWith("ERROR:").contains("title");
        verify(tasks, never()).save(any());
    }

    @Test
    void createTask_blankDescription_returnsError() {
        // Now mandatory — description IS the assignee's prompt. Fast-fail BEFORE the
        // agents-exists check, so no stub needed.
        TaskManagementTool t = tool();
        assertThat(withContext(() -> t.createTask("title", "  ", "agent-x", null)))
                .startsWith("ERROR:").contains("description");
        verify(tasks, never()).save(any());
    }

    @Test
    void updateTaskStatus_unknownStatus_returnsErrorString() {
        TaskManagementTool t = tool();
        String r = withContext(() -> t.updateTaskStatus("task-1", "DONE_MAYBE", "result"));
        assertThat(r).startsWith("ERROR:").contains("DONE_MAYBE");
        verify(tasks, never()).save(any());
    }

    @Test
    void updateTaskStatus_unknownTask_returnsErrorString() {
        when(tasks.findByIdAndOrgId("ghost-task", ORG)).thenReturn(Optional.empty());
        TaskManagementTool t = tool();
        String r = withContext(() -> t.updateTaskStatus("ghost-task", "COMPLETED", "result"));
        assertThat(r).startsWith("ERROR:").contains("ghost-task");
        verify(tasks, never()).save(any());
    }

    @Test
    void updateTaskStatus_terminalState_stampsCompletedAtAndPersistsResult() {
        TaskEntity t = entity("task-1", TEAM_RUN, ORG, TaskStatus.IN_PROGRESS, "agent-x");
        when(tasks.findByIdAndOrgId("task-1", ORG)).thenReturn(Optional.of(t));
        when(tasks.save(any(TaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String r = withContext(() ->
                tool().updateTaskStatus("task-1", "completed", "all done"));

        assertThat(r).isEqualTo("OK");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(t.getResult()).isEqualTo("all done");
        assertThat(t.getCompletedAt()).isNotNull();

        ArgumentCaptor<AgentRunEvent> evt = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus).publish(evt.capture());
        AgentRunEvent ev = evt.getValue();
        assertThat(ev.eventType()).isEqualTo(AgentRunEventType.TASK_UPDATED);
        assertThat(ev.payload()).containsEntry("taskId", "task-1")
                .containsEntry("status", "COMPLETED")
                .containsEntry("result", "all done");
    }

    @Test
    void queryTasks_filtersByStatusAndAssignee() {
        TaskEntity a1 = entity("a1", TEAM_RUN, ORG, TaskStatus.COMPLETED, "agent-a");
        a1.setTitle("done one");
        TaskEntity b1 = entity("b1", TEAM_RUN, ORG, TaskStatus.PENDING, "agent-b");
        b1.setTitle("waiting");
        TaskEntity a2 = entity("a2", TEAM_RUN, ORG, TaskStatus.PENDING, "agent-a");
        a2.setTitle("agent-a pending");
        when(tasks.findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(TEAM_RUN, ORG))
                .thenReturn(List.of(a1, b1, a2));

        String r = withContext(() -> tool().queryTasks("PENDING", "agent-a"));
        assertThat(r).contains("a2").doesNotContain("a1").doesNotContain("b1");
    }

    @Test
    void getTask_taskFromDifferentTeamRun_returnsErrorString() {
        TaskEntity foreign = entity("foreign", "other-team-run", ORG, TaskStatus.PENDING, "agent-x");
        when(tasks.findByIdAndOrgId("foreign", ORG)).thenReturn(Optional.of(foreign));

        String r = withContext(() -> tool().getTask("foreign"));
        assertThat(r).startsWith("ERROR:").contains("does not belong");
    }

    // --- ScopedValue helpers ---

    private static <T> T withContext(java.util.concurrent.Callable<T> c) {
        try {
            return ScopedValue.where(AgentContextHolder.currentRunId, TEAM_RUN)
                    .where(AgentContextHolder.orgId, ORG)
                    .call(c::call);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TaskEntity entity(String id, String teamRunId, String orgId, TaskStatus status, String assignee) {
        TaskEntity e = new TaskEntity();
        e.setId(id);
        e.setTeamRunId(teamRunId);
        e.setOrgId(orgId);
        e.setStatus(status);
        e.setAssigneeAgentId(assignee);
        e.setDependencies(new String[0]);
        return e;
    }
}
