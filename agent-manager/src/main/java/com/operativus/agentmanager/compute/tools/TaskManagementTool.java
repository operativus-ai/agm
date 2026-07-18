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
import com.operativus.agentmanager.core.registry.AgentOperations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Task-management surface for the {@code TeamMode.tasks} coordinator
 *     agent. Methods read the active team-run id from {@link AgentContextHolder#getCurrentRunId()}
 *     so a task created by the coordinator is automatically scoped to the same run as the
 *     coordinator that delegated to it. The {@code orgId} comes from
 *     {@link AgentContextHolder#getOrgId()} for tenant isolation.
 *
 *     <p><b>D9 — Unknown assignee:</b> {@link #createTask} validates the assignee against
 *     the agents table for the caller's org. An unknown id returns an error string for the
 *     LLM to recover from — does NOT throw. The {@code agm.tasks.strict-assignee} flag (TBD)
 *     will switch this to a thrown exception for high-assurance deployments; default is
 *     LLM-recovery.
 *
 *     <p>Auto-discovered as a Spring bean via {@link AgentToolComponent}. Wiring into the
 *     coordinator's {@code ChatClient.tools(...)} list happens in {@code AgentClientFactory}
 *     (REQ-TT-3 wiring follow-up — for now the tool exists, but only the TASKS coordinator
 *     would benefit from binding it; gating logic lives outside this class).
 *
 * State: Stateless (Spring singleton; data is in the repository).
 */
@AgentToolComponent
public class TaskManagementTool {

    private static final Logger log = LoggerFactory.getLogger(TaskManagementTool.class);

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final AgentRunEventBus eventBus;
    private final MeterRegistry meterRegistry;
    private final ObjectProvider<AgentOperations> agentRunnerProvider;

    public TaskManagementTool(TaskRepository taskRepository, AgentRepository agentRepository,
                              AgentRunEventBus eventBus, MeterRegistry meterRegistry,
                              ObjectProvider<AgentOperations> agentRunnerProvider) {
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.eventBus = eventBus;
        this.meterRegistry = meterRegistry;
        this.agentRunnerProvider = agentRunnerProvider;
    }

    @Tool(description = """
            Delegate a task to a team member and wait for their response. Pass a short title,
            a description (which becomes the assignee's prompt), and the assigneeAgentId.
            The dependencies parameter is metadata-only — order tasks by calling createTask
            in sequence and feeding earlier results into later descriptions. Returns the
            assignee's response text on success, or an ERROR string when validation fails or
            the assignee throws.
            """)
    public String createTask(
            @ToolParam(description = "Short human-readable title for the task") String title,
            @ToolParam(description = "The prompt for the assignee; include any context from earlier tasks") String description,
            @ToolParam(description = "Agent id of the team member who will execute this task") String assigneeAgentId,
            @ToolParam(description = "Optional task ids this task semantically depends on (metadata only)") List<String> dependencies
    ) {
        String teamRunId = requireTeamRunId();
        String orgId = requireOrgId();
        String userId = AgentContextHolder.getUserId();
        String sessionId = AgentContextHolder.getSessionId();
        if (title == null || title.isBlank()) {
            return "ERROR: title is required";
        }
        if (assigneeAgentId == null || assigneeAgentId.isBlank()) {
            return "ERROR: assigneeAgentId is required";
        }
        if (description == null || description.isBlank()) {
            return "ERROR: description is required — it is fed to the assignee as the prompt";
        }
        if (!agentRepository.existsByIdAndOrgId(assigneeAgentId, orgId)) {
            log.warn("team.tasks.unknown_assignee team={} requestedAgentId={}", teamRunId, assigneeAgentId);
            return "ERROR: assignee '" + assigneeAgentId + "' is not a member of this team's org. "
                    + "Use queryTasks or pick a different agent id.";
        }

        // 1. Persist PENDING row + emit TASK_CREATED.
        TaskEntity t = new TaskEntity();
        t.setId(UUID.randomUUID().toString());
        t.setTeamRunId(teamRunId);
        t.setOrgId(orgId);
        t.setTitle(title.trim());
        t.setDescription(description);
        t.setAssigneeAgentId(assigneeAgentId);
        t.setStatus(TaskStatus.PENDING);
        t.setDependencies(dependencies == null ? new String[0] : dependencies.toArray(new String[0]));
        TaskEntity saved = taskRepository.save(t);
        emitTaskCreated(saved);
        Counter.builder("agm.team.tasks.created")
                .tag("org", orgId)
                .tag("assignee", assigneeAgentId)
                .register(meterRegistry).increment();

        // 2. Mark IN_PROGRESS via atomic CAS — matches the original D5 design even though
        //    the worker loop never wins the race anymore (this tool wins synchronously).
        String workerId = "tool-" + teamRunId;
        int affected = taskRepository.atomicallyDispatch(saved.getId(), orgId, workerId, LocalDateTime.now());
        if (affected == 0) {
            return "ERROR: task " + saved.getId() + " could not be claimed (already in progress?)";
        }
        TaskEntity claimed = taskRepository.findByIdAndOrgId(saved.getId(), orgId).orElse(saved);
        emitTaskUpdated(claimed);

        // 3. Synchronously dispatch the assignee. Mirrors DelegationTool.delegate_to_agent —
        //    runner.run is called on a NON-team member, so no recursion into team
        //    orchestration. The assignee's chat turn produces the result we return inline
        //    to the coordinator's chat loop.
        Instant startedAt = Instant.now();
        try {
            RunResponse response = agentRunnerProvider.getObject().run(
                    assigneeAgentId, description, List.of(), sessionId, userId, orgId, false, null);
            String content = response != null && response.content() != null ? response.content() : "";

            claimed.setStatus(TaskStatus.COMPLETED);
            claimed.setResult(content);
            claimed.setCompletedAt(LocalDateTime.now());
            taskRepository.save(claimed);
            emitTaskUpdated(claimed);
            Counter.builder("agm.team.tasks.completed")
                    .tag("org", orgId).tag("assignee", assigneeAgentId)
                    .register(meterRegistry).increment();
            Timer.builder("agm.team.tasks.duration")
                    .tag("assignee", assigneeAgentId).tag("outcome", "completed")
                    .register(meterRegistry)
                    .record(Duration.between(startedAt, Instant.now()).toMillis(), TimeUnit.MILLISECONDS);
            return content;
        } catch (RuntimeException ex) {
            log.warn("Task {} dispatch to {} failed: {}", claimed.getId(), assigneeAgentId, ex.getMessage());
            claimed.setStatus(TaskStatus.FAILED);
            claimed.setResult(ex.getMessage());
            claimed.setCompletedAt(LocalDateTime.now());
            taskRepository.save(claimed);
            emitTaskUpdated(claimed);
            Counter.builder("agm.team.tasks.failed")
                    .tag("org", orgId).tag("assignee", assigneeAgentId)
                    .register(meterRegistry).increment();
            return "ERROR: task assignee '" + assigneeAgentId + "' failed: " + ex.getMessage();
        }
    }

    @Tool(description = """
            Update the status of a task. Status values: IN_PROGRESS, COMPLETED, FAILED, BLOCKED.
            Pass an optional result string explaining the outcome. Returns OK on success or
            an error string if the task is not found or the status value is invalid.
            """)
    public String updateTaskStatus(
            @ToolParam(description = "Id of the task to update") String taskId,
            @ToolParam(description = "New status value") String status,
            @ToolParam(description = "Optional result / explanation") String result
    ) {
        String orgId = requireOrgId();
        TaskStatus parsed;
        try {
            parsed = TaskStatus.valueOf(status == null ? "" : status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return "ERROR: unknown status '" + status + "'. Valid: PENDING, IN_PROGRESS, COMPLETED, FAILED, BLOCKED";
        }
        TaskEntity t = taskRepository.findByIdAndOrgId(taskId, orgId).orElse(null);
        if (t == null) {
            return "ERROR: task '" + taskId + "' not found";
        }
        t.setStatus(parsed);
        if (result != null) {
            t.setResult(result);
        }
        if (parsed == TaskStatus.COMPLETED || parsed == TaskStatus.FAILED || parsed == TaskStatus.BLOCKED) {
            t.setCompletedAt(LocalDateTime.now());
        }
        taskRepository.save(t);
        emitTaskUpdated(t);
        return "OK";
    }

    @Tool(description = """
            Query the team-run's tasks. Optional filters: status (PENDING/IN_PROGRESS/COMPLETED/
            FAILED/BLOCKED) and/or assigneeAgentId. Returns a newline-delimited list of
            'taskId | status | assignee | title' rows, or 'no matching tasks' when the result is empty.
            """)
    public String queryTasks(
            @ToolParam(description = "Optional status filter") String status,
            @ToolParam(description = "Optional assignee agent id filter") String assigneeAgentId
    ) {
        String teamRunId = requireTeamRunId();
        String orgId = requireOrgId();
        List<TaskEntity> rows = taskRepository.findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(teamRunId, orgId);
        TaskStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = TaskStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return "ERROR: unknown status filter '" + status + "'";
            }
        }
        final TaskStatus sf = statusFilter;
        final String af = (assigneeAgentId == null || assigneeAgentId.isBlank()) ? null : assigneeAgentId;
        List<TaskEntity> filtered = rows.stream()
                .filter(t -> sf == null || t.getStatus() == sf)
                .filter(t -> af == null || af.equals(t.getAssigneeAgentId()))
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return "no matching tasks";
        }
        return filtered.stream()
                .map(t -> t.getId() + " | " + t.getStatus() + " | "
                        + (t.getAssigneeAgentId() == null ? "-" : t.getAssigneeAgentId())
                        + " | " + t.getTitle())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = """
            Fetch full details of a single task by id, including title, description, assignee,
            status, dependencies, and result. Returns an error string if the task is not found
            or belongs to a different team-run.
            """)
    public String getTask(@ToolParam(description = "Id of the task to fetch") String taskId) {
        String orgId = requireOrgId();
        String teamRunId = requireTeamRunId();
        TaskEntity t = taskRepository.findByIdAndOrgId(taskId, orgId).orElse(null);
        if (t == null) {
            return "ERROR: task '" + taskId + "' not found";
        }
        if (!teamRunId.equals(t.getTeamRunId())) {
            // Defense in depth — same-org but different team-run is also a no-no.
            return "ERROR: task '" + taskId + "' does not belong to this team-run";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(t.getId()).append("\n");
        sb.append("title: ").append(t.getTitle()).append("\n");
        if (t.getDescription() != null) sb.append("description: ").append(t.getDescription()).append("\n");
        sb.append("assignee: ").append(t.getAssigneeAgentId() == null ? "-" : t.getAssigneeAgentId()).append("\n");
        sb.append("status: ").append(t.getStatus()).append("\n");
        String[] deps = t.getDependencies();
        sb.append("dependencies: ").append(deps == null || deps.length == 0 ? "[]" : String.join(",", deps)).append("\n");
        if (t.getResult() != null) sb.append("result: ").append(t.getResult());
        return sb.toString();
    }

    /** REQ-TT-4 — fan out a TASK_CREATED event through AgentRunEventBus so SSE subscribers,
     *  Spring listeners, and the agent_run_events audit table all observe the new row. */
    private void emitTaskCreated(TaskEntity task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", task.getId());
        payload.put("title", task.getTitle());
        payload.put("assigneeAgentId", task.getAssigneeAgentId());
        payload.put("dependencies", task.getDependencies() == null
                ? List.of() : List.of(task.getDependencies()));
        eventBus.publish(new AgentRunEvent(
                AgentRunEventType.TASK_CREATED,
                task.getTeamRunId(),
                task.getAssigneeAgentId(),
                null,
                AgentContextHolder.getSessionId(),
                task.getOrgId(),
                AgentContextHolder.getOrchestrationDepth(),
                payload,
                Instant.now()));
    }

    /** REQ-TT-4 — fan out a TASK_UPDATED event on tool-driven lifecycle transitions
     *  (orchestrator-driven transitions emit the same shape from TasksOrchestrator). */
    private void emitTaskUpdated(TaskEntity task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", task.getId());
        payload.put("status", task.getStatus().name());
        if (task.getResult() != null) payload.put("result", task.getResult());
        eventBus.publish(new AgentRunEvent(
                AgentRunEventType.TASK_UPDATED,
                task.getTeamRunId(),
                task.getAssigneeAgentId(),
                null,
                AgentContextHolder.getSessionId(),
                task.getOrgId(),
                AgentContextHolder.getOrchestrationDepth(),
                payload,
                Instant.now()));
    }

    private static String requireTeamRunId() {
        String id = AgentContextHolder.getCurrentRunId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "TaskManagementTool invoked outside a team-run scope — no currentRunId bound");
        }
        return id;
    }

    private static String requireOrgId() {
        String id = AgentContextHolder.getOrgId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "TaskManagementTool invoked without an orgId bound");
        }
        return id;
    }
}
