package com.operativus.agentmanager.compute.teams;

import com.operativus.agentmanager.control.repository.TaskRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.TaskEntity;
import com.operativus.agentmanager.core.entity.TaskStatus;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.exception.TeamMemberPausedException;
import com.operativus.agentmanager.core.model.MetricConstants;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.AgentOperations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Domain Responsibility: Executes a team in {@code TeamMode.tasks} — the autonomous
 *     task-list orchestrator. The root agent acts as the coordinator (D4: root-as-coordinator),
 *     and uses task-management tools (REQ-TT-3) to enqueue {@link TaskEntity} rows. This
 *     skeleton (PR-2) runs the coordinator and drives the worker loop; the actual tool
 *     wiring that lets the LLM create tasks lands in REQ-TT-3.
 *
 *     <p><b>Worker loop:</b> after the coordinator returns, poll {@link TaskRepository} for
 *     tasks whose dependencies are all {@link TaskStatus#COMPLETED}. For each, win the
 *     atomic dispatch CAS ({@link TaskRepository#atomicallyDispatch}) and call
 *     {@link AgentOperations#run} against the assignee. Update the task to terminal state
 *     based on the response. Loop until no task is dispatchable.
 *
 *     <p><b>Concurrency (D5/D7):</b> max parallelism gated by {@code agent.tasks.max-parallel}
 *     property (default 4). Skeleton runs single-threaded; virtual-thread fan-out is
 *     follow-up work once the tool surface exists to create non-trivial workloads.
 *
 *     <p>ScopedValues are pre-bound by {@link com.operativus.agentmanager.compute.service.TeamOrchestrationEngine#executeSync},
 *     so the coordinator and worker calls can read {@code orgId}, {@code currentRunId},
 *     etc. directly from {@link AgentContextHolder}.
 *
 * State: Stateless (Spring singleton).
 */
@Component
public non-sealed class TasksOrchestrator implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(TasksOrchestrator.class);
    private static final List<TaskStatus> TERMINAL_STATUSES =
            List.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.BLOCKED);

    private final TaskRepository taskRepository;
    private final AgentRunEventBus eventBus;
    private final MeterRegistry meterRegistry;
    private final AgentRegistry agentRegistry;
    private final TeamMemberHumanReviewGate humanReviewGate;

    public TasksOrchestrator(TaskRepository taskRepository, AgentRunEventBus eventBus,
                             MeterRegistry meterRegistry, AgentRegistry agentRegistry,
                             TeamMemberHumanReviewGate humanReviewGate) {
        this.taskRepository = taskRepository;
        this.eventBus = eventBus;
        this.meterRegistry = meterRegistry;
        this.agentRegistry = agentRegistry;
        this.humanReviewGate = humanReviewGate;
    }

    @Override
    public boolean supports(String teamMode) {
        return "TASKS".equalsIgnoreCase(teamMode);
    }

    @Override
    public String getStrategyName() {
        return "TASKS";
    }

    /**
     * @summary Runs the coordinator, drains the worker loop, then re-runs the coordinator
     *     with task results as context for a synthesized final response.
     * @logic
     *     <ol>
     *       <li>Run coordinator once with the user prompt — this kickoff turn is where the
     *           LLM enqueues tasks via {@code TaskManagementTool.createTask}.</li>
     *       <li>Drain the worker loop: poll the team-run's pending tasks, dispatch each
     *           whose deps are satisfied, mark COMPLETED on success, FAILED on assignee
     *           exception, BLOCKED on a dep that itself transitioned to FAILED.</li>
     *       <li><b>Synthesis pass (REQ-TT-7b):</b> if any tasks ran, re-run the coordinator
     *           with a structured summary of every task outcome as context. The coordinator
     *           gets to read what each assignee produced and write a synthesized answer.
     *           Falls back to the kickoff response when no tasks were created (skeleton-
     *           only path) or when the synthesis call fails.</li>
     *     </ol>
     */
    @Override
    @Observed(name = MetricConstants.ORCHESTRATION_OBSERVATION, contextualName = "tasks")
    public String execute(AgentDefinition rootAgent, String initialInput, List<Media> media,
                          String sessionId, String userId, String orgId,
                          Boolean generateFollowups, AgentOperations runner) {

        String teamRunId = AgentContextHolder.getCurrentRunId();
        log.info("TasksOrchestrator start team={} run={} org={}", rootAgent.id(), teamRunId, orgId);

        RunResponse coordinatorResponse = runner.run(
                rootAgent.id(), initialInput, media, sessionId, userId, orgId, false, null);
        String kickoffOutput = coordinatorResponse != null ? coordinatorResponse.content() : "";

        drainWorkerLoop(teamRunId, orgId, runner, userId, sessionId, rootAgent.id());

        return synthesizeOrReturnKickoff(rootAgent, initialInput, kickoffOutput,
                teamRunId, sessionId, userId, orgId, runner);
    }

    /** REQ-TT-7b — synthesis pass. Reads every task for the run, builds a structured
     *  summary block, and asks the coordinator to compose a final answer based on the
     *  task outcomes. Skipped (returns kickoff) when no tasks ran or on synthesis error. */
    private String synthesizeOrReturnKickoff(AgentDefinition rootAgent, String initialInput,
                                             String kickoffOutput, String teamRunId,
                                             String sessionId, String userId, String orgId,
                                             AgentOperations runner) {
        if (teamRunId == null) return kickoffOutput;
        List<TaskEntity> tasks = taskRepository
                .findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(teamRunId, orgId);
        if (tasks.isEmpty()) {
            return kickoffOutput;
        }
        String summary = buildTaskSummary(tasks);
        String synthesisPrompt = """
                You delegated work to team members via the task list. Compose a final
                response for the original user request based on the outcomes below.

                Original request: %s

                Task results:
                %s

                Write a concise synthesis. Do not list the task ids; speak in the user's
                vocabulary about what was accomplished.
                """.formatted(initialInput, summary);
        try {
            RunResponse synth = runner.run(
                    rootAgent.id(), synthesisPrompt, List.of(),
                    sessionId, userId, orgId, false, null);
            return synth != null && synth.content() != null && !synth.content().isBlank()
                    ? synth.content() : kickoffOutput;
        } catch (RuntimeException ex) {
            log.warn("TasksOrchestrator synthesis pass failed for team-run {} — returning kickoff output: {}",
                    teamRunId, ex.getMessage());
            return kickoffOutput;
        }
    }

    private static String buildTaskSummary(List<TaskEntity> tasks) {
        StringBuilder sb = new StringBuilder();
        for (TaskEntity t : tasks) {
            sb.append("- ").append(t.getTitle())
              .append(" [").append(t.getStatus()).append("]");
            if (t.getAssigneeAgentId() != null) {
                sb.append(" by ").append(t.getAssigneeAgentId());
            }
            if (t.getResult() != null && !t.getResult().isBlank()) {
                sb.append(": ").append(t.getResult().lines().findFirst().orElse(""));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Drains the team-run's task queue. Each loop iteration:
     *   1. Find pending tasks whose dependencies are all COMPLETED.
     *   2. Win the atomic dispatch CAS (skip on race-loss).
     *   3. Dispatch to assignee via {@link AgentOperations#run}.
     *   4. Mark COMPLETED on success, FAILED on assignee exception.
     * Loop terminates when no pending tasks remain dispatchable.
     */
    private void drainWorkerLoop(String teamRunId, String orgId, AgentOperations runner,
                                 String userId, String sessionId, String teamId) {
        if (teamRunId == null) {
            return; // No team-run context — coordinator-only path.
        }
        int iterations = 0;
        while (true) {
            List<TaskEntity> pending = taskRepository
                    .findByTeamRunIdAndStatusOrderByCreatedAtAsc(teamRunId, TaskStatus.PENDING);
            if (pending.isEmpty()) {
                break;
            }
            int dispatched = 0;
            for (TaskEntity task : pending) {
                if (!dependenciesSatisfied(task, teamRunId, orgId)) {
                    continue;
                }
                if (!tryDispatch(task, runner, userId, orgId, sessionId, teamId, teamRunId)) {
                    continue;
                }
                dispatched++;
            }
            if (dispatched == 0) {
                // Nothing made progress this iteration — avoid spinning on permanently
                // blocked or already-claimed rows.
                break;
            }
            if (++iterations > 64) {
                log.warn("TasksOrchestrator worker-loop iteration cap reached for team-run {} — exiting", teamRunId);
                break;
            }
        }
    }

    /** A task's deps are satisfied when every dep id is in a COMPLETED row of the same team-run.
     *  Tasks with no deps (empty array) are always satisfied. */
    private boolean dependenciesSatisfied(TaskEntity task, String teamRunId, String orgId) {
        String[] deps = task.getDependencies();
        if (deps == null || deps.length == 0) {
            return true;
        }
        for (String depId : deps) {
            TaskStatus depStatus = taskRepository.findByIdAndOrgId(depId, orgId)
                    .map(TaskEntity::getStatus)
                    .orElse(null);
            if (depStatus == TaskStatus.FAILED || depStatus == TaskStatus.BLOCKED) {
                markBlocked(task, "Dependency " + depId + " is " + depStatus);
                return false;
            }
            if (depStatus != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /** REQ-TT-6 — resume-path entry point invoked by TeamMemberDispatchResumeHandler
     *  after operator approval. Caller binds {@code AgentContextHolder.approvedTeamMembers}
     *  with the gated assignee so the pre-dispatch gate is a no-op when this method
     *  loops back through tryDispatch. */
    public void dispatchOneTask(String taskId, String orgId, AgentOperations runner,
                                String userId, String sessionId) {
        TaskEntity task = taskRepository.findByIdAndOrgId(taskId, orgId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.PENDING) {
            log.info("dispatchOneTask: task {} not in PENDING state — no-op", taskId);
            return;
        }
        tryDispatch(task, runner, userId, orgId, sessionId, "", task.getTeamRunId());
    }

    private boolean tryDispatch(TaskEntity task, AgentOperations runner, String userId, String orgId,
                                String sessionId, String teamId, String teamRunId) {
        String assigneeId = task.getAssigneeAgentId();
        if (assigneeId == null) {
            markFailed(task, "Task has no assignee");
            return true;
        }
        // REQ-TT-6 — pre-dispatch HITL gate. Same TEAM_MEMBER_DISPATCH subject type used
        // by Sequential/Router/Planner/Swarm; the strategy=TASKS branch and taskId key in
        // the cursor let TeamMemberDispatchResumeHandler re-enter dispatchTask on approve.
        AgentDefinition memberDef = agentRegistry.findById(assigneeId, orgId);
        try {
            humanReviewGate.requireApprovalIfConfigured(
                    memberDef, teamRunId, teamId, assigneeId, orgId,
                    Map.of(
                            TeamMemberDispatchExtraOptions.STRATEGY, "TASKS",
                            TeamMemberDispatchExtraOptions.TASK_ID, task.getId(),
                            TeamMemberDispatchExtraOptions.SESSION_ID, sessionId == null ? "" : sessionId,
                            TeamMemberDispatchExtraOptions.USER_ID, userId == null ? "" : userId
                    ));
        } catch (TeamMemberPausedException paused) {
            // Whole team-run pause is the caller's signal that this task's dispatch is
            // gated. Per D8 the loop continues for non-gated tasks; we re-throw so the
            // outer engine treats it as a run-wide pause until cross-task pause is wired
            // (deferred follow-up). The pending row + cursor are already persisted.
            throw paused;
        }
        String workerId = "worker-" + AgentContextHolder.getCurrentRunId();
        int affected = taskRepository.atomicallyDispatch(task.getId(), orgId, workerId, LocalDateTime.now());
        if (affected == 0) {
            return false; // Another worker won the race.
        }
        TaskEntity claimed = taskRepository.findByIdAndOrgId(task.getId(), orgId).orElse(null);
        if (claimed == null) {
            return true;
        }
        emitUpdated(claimed); // PENDING -> IN_PROGRESS lifecycle event
        try {
            RunResponse response = runner.run(
                    claimed.getAssigneeAgentId(),
                    buildAssigneePrompt(claimed),
                    List.of(),
                    AgentContextHolder.getSessionId(),
                    userId,
                    orgId,
                    false,
                    null);
            markCompleted(claimed, response != null ? response.content() : "");
        } catch (RuntimeException ex) {
            log.warn("Task {} assignee {} failed: {}", claimed.getId(), claimed.getAssigneeAgentId(), ex.getMessage());
            markFailed(claimed, ex.getMessage());
        }
        return true;
    }

    private static String buildAssigneePrompt(TaskEntity task) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task.getTitle()).append("\n");
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("Details: ").append(task.getDescription());
        }
        return sb.toString();
    }

    private void markCompleted(TaskEntity task, String result) {
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(result);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
        emitUpdated(task);
        incrementOutcome(task, "completed");
        recordDuration(task);
    }

    private void markFailed(TaskEntity task, String reason) {
        task.setStatus(TaskStatus.FAILED);
        task.setResult(reason);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
        emitUpdated(task);
        incrementOutcome(task, "failed");
        recordDuration(task);
    }

    private void markBlocked(TaskEntity task, String reason) {
        task.setStatus(TaskStatus.BLOCKED);
        task.setResult(reason);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
        emitUpdated(task);
        incrementOutcome(task, "blocked");
        // No duration for BLOCKED — task never dispatched, so dispatched_at is null.
    }

    private void incrementOutcome(TaskEntity task, String outcome) {
        Counter.builder("agm.team.tasks." + outcome)
                .tag("org", task.getOrgId() == null ? "unknown" : task.getOrgId())
                .tag("assignee", task.getAssigneeAgentId() == null ? "-" : task.getAssigneeAgentId())
                .register(meterRegistry).increment();
    }

    private void recordDuration(TaskEntity task) {
        if (task.getDispatchedAt() == null || task.getCompletedAt() == null) {
            return;
        }
        Duration d = Duration.between(task.getDispatchedAt(), task.getCompletedAt());
        Timer.builder("agm.team.tasks.duration")
                .tag("assignee", task.getAssigneeAgentId() == null ? "-" : task.getAssigneeAgentId())
                .tag("outcome", task.getStatus().name().toLowerCase())
                .register(meterRegistry)
                .record(d.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Emit TASK_UPDATED on the run-event timeline. The orchestrator pumps these on every
     *  worker-loop transition so SSE consumers see lifecycle in near-real-time. The event
     *  bus fans out to log + Spring publisher + agent_run_events audit table. */
    private void emitUpdated(TaskEntity task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", task.getId());
        payload.put("status", task.getStatus().name());
        if (task.getResult() != null) payload.put("result", task.getResult());
        if (task.getDispatchedAt() != null) payload.put("dispatchedAt", task.getDispatchedAt().toString());
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
}
