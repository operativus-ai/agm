package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.compute.service.AgentRunFinalizer;
import com.operativus.agentmanager.compute.teams.PlannerOrchestrator;
import com.operativus.agentmanager.compute.teams.RouterOrchestrator;
import com.operativus.agentmanager.compute.teams.SequentialOrchestrator;
import com.operativus.agentmanager.compute.teams.TasksOrchestrator;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.repository.TaskRepository;
import com.operativus.agentmanager.core.entity.TaskEntity;
import com.operativus.agentmanager.core.entity.TaskStatus;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.spi.HumanReviewResumeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Domain Responsibility: REQ-HR follow-up — {@link HumanReviewResumeHandler}
 *     implementation for {@code subjectType=TEAM_MEMBER_DISPATCH}. Reads the
 *     resume cursor from the pending row's options map (populated by
 *     {@link com.operativus.agentmanager.compute.teams.TeamMemberHumanReviewGate}
 *     at pause time), dispatches by strategy + decision, and re-enters the
 *     orchestrator at the right index with appropriate ScopedValue context.
 *
 *     <p><strong>Strategy support:</strong> Only {@code SEQUENTIAL} is wired in
 *     this PR. {@code ROUTER} / {@code PLANNER} / {@code SWARM} will follow in
 *     their own PRs. When the cursor's {@code strategy} key matches an
 *     unsupported orchestrator, the handler logs at WARN and returns — the
 *     pending row is already settled; the team run sits in PAUSED until the
 *     follow-on PR lands or an operator cancels via another path.
 *
 *     <p><strong>Decision routing:</strong>
 *     <ul>
 *       <li>{@code APPROVE} / {@code AUTO_APPROVED} — seed
 *           {@link AgentContextHolder#approvedTeamMembers} with the gated
 *           member id, call {@code SequentialOrchestrator.resumeAt} at
 *           {@code memberIndex}, finalize the team run with the result.</li>
 *       <li>{@code REJECT} / {@code AUTO_REJECTED} — apply the row's
 *           {@code on_reject} policy. SKIP resumes at {@code memberIndex + 1}.
 *           CANCEL marks the team run CANCELLED. ELSE_BRANCH is unsupported
 *           for team dispatch (no else-step concept) and falls back to SKIP.</li>
 *       <li>{@code CANCELLED} — mark the team run CANCELLED. No resume.</li>
 *     </ul>
 *
 *     <p><strong>Cycle-breaking:</strong> Spring DI cycles between the resume
 *     handler and {@link SequentialOrchestrator}/{@link AgentOperations} are
 *     avoided via {@link ApplicationContext} lazy bean lookup (mirrors
 *     {@code WorkflowStepResumeHandler}).
 *
 *     <p><strong>Failure mode:</strong> resume errors are caught and logged.
 *     The pending row is already settled by {@code HumanReviewService.decide}
 *     before this is called — a resume failure leaves the team run in PAUSED
 *     with no auto-retry. Operators can re-trigger via /cancel or by
 *     re-posting decide on the pending row's id (idempotent — second call is
 *     a no-op).
 *
 * State: Stateless (Spring bean).
 */
@Component
public class TeamMemberDispatchResumeHandler implements HumanReviewResumeHandler {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberDispatchResumeHandler.class);
    private static final String CANCEL_REASON = "HUMAN_REVIEW_REJECTED_BY_OPERATOR";

    private final ApplicationContext applicationContext;
    private final RunRepository runRepository;

    public TeamMemberDispatchResumeHandler(ApplicationContext applicationContext,
                                           RunRepository runRepository) {
        this.applicationContext = applicationContext;
        this.runRepository = runRepository;
    }

    @Override
    public HumanReviewSubjectType subjectType() {
        return HumanReviewSubjectType.TEAM_MEMBER_DISPATCH;
    }

    @Override
    public void onDecided(HumanReviewPending pending, HumanReviewDecision decision) {
        String teamRunId = pending.getRunId();
        Map<String, Object> opts = pending.getOptions();
        if (opts == null) {
            log.warn("TeamMemberDispatchResumeHandler: pending {} has no options map; cannot resume",
                    pending.getId());
            return;
        }

        Cursor cursor = readCursor(opts);
        if (cursor == null) {
            log.warn("TeamMemberDispatchResumeHandler: pending {} options missing required cursor keys; cannot resume",
                    pending.getId());
            return;
        }

        if (decision == HumanReviewDecision.CANCELLED) {
            finalizeCancel(teamRunId, "Cancelled by operator decision");
            return;
        }

        if (decision.isReject()) {
            OnRejectPolicy policy = readOnRejectPolicy(opts);
            switch (policy) {
                case CANCEL -> finalizeCancel(teamRunId, "Rejected by operator (on_reject=CANCEL)");
                case ELSE_BRANCH, SKIP -> resumeAfterSkip(pending, cursor);
            }
            return;
        }

        if (decision.isApprove()) {
            resumeWithApproval(pending, cursor);
            return;
        }

        log.warn("TeamMemberDispatchResumeHandler: unhandled decision {} for pending {}; no-op",
                decision, pending.getId());
    }

    private void resumeWithApproval(HumanReviewPending pending, Cursor cursor) {
        if ("TASKS".equals(cursor.strategy)) {
            resumeTasksDispatch(pending, cursor, true);
            return;
        }
        if ("ROUTER".equals(cursor.strategy)) {
            resumeRouterDispatch(pending, cursor);
            return;
        }
        if ("PLANNER".equals(cursor.strategy)) {
            resumePlannerDispatch(pending, cursor, cursor.memberIndex, Set.of(cursor.memberAgentId));
            return;
        }
        if (!"SEQUENTIAL".equals(cursor.strategy)) {
            log.warn("TeamMemberDispatchResumeHandler: strategy {} not yet wired for resume (pending {}); "
                    + "pending row is settled but team run stays in PAUSED",
                    cursor.strategy, pending.getId());
            return;
        }
        executeResume(pending, cursor, cursor.memberIndex,
                Set.of(cursor.memberAgentId)); // seed approved-set so gate doesn't re-fire
    }

    private void resumeAfterSkip(HumanReviewPending pending, Cursor cursor) {
        if ("TASKS".equals(cursor.strategy)) {
            resumeTasksDispatch(pending, cursor, false);
            return;
        }
        if ("ROUTER".equals(cursor.strategy)) {
            // Router has no "skip and continue" semantic — only one dispatch per run.
            // Rejection finalizes the team run as CANCELLED.
            finalizeCancel(pending.getRunId(), "Router dispatch rejected by operator");
            return;
        }
        if ("PLANNER".equals(cursor.strategy)) {
            resumePlannerDispatch(pending, cursor, cursor.memberIndex + 1, Set.of());
            return;
        }
        if (!"SEQUENTIAL".equals(cursor.strategy)) {
            log.warn("TeamMemberDispatchResumeHandler: strategy {} not yet wired for resume (pending {}); "
                    + "pending row is settled but team run stays in PAUSED",
                    cursor.strategy, pending.getId());
            return;
        }
        executeResume(pending, cursor, cursor.memberIndex + 1, Set.of());
    }

    /**
     * PLANNER-strategy resume branch. Re-enters the plan at {@code fromStepNumber}
     * using the plan persisted in the cursor at pause time (so the resumed run sees
     * the SAME plan the operator approved against — no LLM regeneration).
     *
     * @param approvedTeamMembers Approved-set seed (member id whose gate already
     *     fired; empty for the skip path).
     */
    private void resumePlannerDispatch(HumanReviewPending pending, Cursor cursor,
                                       int fromStepNumber,
                                       Set<String> approvedTeamMembers) {
        if (cursor.planRaw == null) {
            log.warn("TeamMemberDispatchResumeHandler: PLANNER cursor missing plan for pending {} — no-op",
                    pending.getId());
            return;
        }
        String teamRunId = pending.getRunId();
        AgentRun teamRun = ((org.springframework.data.repository.CrudRepository<AgentRun, String>) runRepository)
                .findById(teamRunId).orElse(null);
        if (teamRun == null) {
            log.warn("TeamMemberDispatchResumeHandler: PLANNER team run {} not found for pending {}; no-op",
                    teamRunId, pending.getId());
            return;
        }
        String orgId = teamRun.getOrgId();
        AgentRegistry agentRegistry = applicationContext.getBean(AgentRegistry.class);
        PlannerOrchestrator orchestrator = applicationContext.getBean(PlannerOrchestrator.class);
        AgentOperations runner = applicationContext.getBean(AgentOperations.class);
        AgentRunFinalizer finalizer = applicationContext.getBean(AgentRunFinalizer.class);
        AgentDefinition team = agentRegistry.findById(teamRun.getAgentId(), orgId);
        if (team == null) {
            log.warn("TeamMemberDispatchResumeHandler: PLANNER team {} not resolvable (org={}); no-op",
                    teamRun.getAgentId(), orgId);
            return;
        }
        PlannerOrchestrator.ExecutionPlan plan = PlannerOrchestrator.decodePlan(cursor.planRaw);
        try {
            String result = ScopedValue
                    .where(AgentContextHolder.currentRunId, teamRunId)
                    .where(AgentContextHolder.orgId, orgId)
                    .where(AgentContextHolder.userId, cursor.userId)
                    .where(AgentContextHolder.sessionId, cursor.sessionId)
                    .where(AgentContextHolder.approvedTeamMembers, approvedTeamMembers)
                    .where(AgentContextHolder.orchestrationDepth, 1)
                    .call(() -> orchestrator.resumeAt(team, plan, fromStepNumber, null,
                            cursor.sessionId, cursor.userId, orgId, runner));
            finalizer.finalizeRun(teamRunId, RunStatus.COMPLETED, result, null, null);
        } catch (RuntimeException ex) {
            log.error("TeamMemberDispatchResumeHandler: PLANNER resume threw for team run {} (pending {}): {}",
                    teamRunId, pending.getId(), ex.toString(), ex);
            finalizer.finalizeRun(teamRunId, RunStatus.FAILED,
                    "Planner resume failed: " + ex.getMessage(), null, null);
        } catch (Exception ex) {
            log.error("TeamMemberDispatchResumeHandler: PLANNER resume threw checked exception (unexpected) for pending {}: {}",
                    pending.getId(), ex.toString(), ex);
            finalizer.finalizeRun(teamRunId, RunStatus.FAILED,
                    "Planner resume failed: " + ex.getMessage(), null, null);
        }
    }

    /**
     * ROUTER-strategy resume branch — operator approved the gated target. Router's
     * dispatch decision is LLM-driven and may pick a different target on re-run, so
     * the safest semantic is to re-run the orchestrator with {@code approvedTeamMembers}
     * seeded with the originally-gated id. Two outcomes:
     * <ul>
     *   <li>Router picks the same target → gate is no-op (id in approved set), dispatch
     *       proceeds.</li>
     *   <li>Router picks a different target → that new target's humanReview config
     *       gates independently (may fire again, may not — depends on its config).</li>
     * </ul>
     */
    private void resumeRouterDispatch(HumanReviewPending pending, Cursor cursor) {
        String teamRunId = pending.getRunId();
        AgentRun teamRun = ((org.springframework.data.repository.CrudRepository<AgentRun, String>) runRepository)
                .findById(teamRunId).orElse(null);
        if (teamRun == null) {
            log.warn("TeamMemberDispatchResumeHandler: ROUTER team run {} not found for pending {}; no-op",
                    teamRunId, pending.getId());
            return;
        }
        String orgId = teamRun.getOrgId();
        AgentRegistry agentRegistry = applicationContext.getBean(AgentRegistry.class);
        RouterOrchestrator orchestrator = applicationContext.getBean(RouterOrchestrator.class);
        AgentOperations runner = applicationContext.getBean(AgentOperations.class);
        AgentRunFinalizer finalizer = applicationContext.getBean(AgentRunFinalizer.class);
        AgentDefinition team = agentRegistry.findById(teamRun.getAgentId(), orgId);
        if (team == null) {
            log.warn("TeamMemberDispatchResumeHandler: ROUTER team {} not resolvable (org={}); no-op",
                    teamRun.getAgentId(), orgId);
            return;
        }
        try {
            String result = ScopedValue
                    .where(AgentContextHolder.currentRunId, teamRunId)
                    .where(AgentContextHolder.orgId, orgId)
                    .where(AgentContextHolder.userId, cursor.userId)
                    .where(AgentContextHolder.sessionId, cursor.sessionId)
                    .where(AgentContextHolder.approvedTeamMembers, Set.of(cursor.memberAgentId))
                    .where(AgentContextHolder.orchestrationDepth, 1)
                    .call(() -> orchestrator.execute(team, cursor.currentInput, null,
                            cursor.sessionId, cursor.userId, orgId, Boolean.FALSE, runner));
            finalizer.finalizeRun(teamRunId, RunStatus.COMPLETED, result, null, null);
        } catch (RuntimeException ex) {
            log.error("TeamMemberDispatchResumeHandler: ROUTER resume threw for team run {} (pending {}): {}",
                    teamRunId, pending.getId(), ex.toString(), ex);
            finalizer.finalizeRun(teamRunId, RunStatus.FAILED,
                    "Router resume failed: " + ex.getMessage(), null, null);
        } catch (Exception ex) {
            log.error("TeamMemberDispatchResumeHandler: ROUTER resume threw checked exception (unexpected) for pending {}: {}",
                    pending.getId(), ex.toString(), ex);
            finalizer.finalizeRun(teamRunId, RunStatus.FAILED,
                    "Router resume failed: " + ex.getMessage(), null, null);
        }
    }

    /**
     * REQ-TT-6 — TASKS-strategy resume branch.
     * <ul>
     *   <li><b>approve=true</b> — seed the approved-team-members set with the gated
     *       assignee, then call {@code TasksOrchestrator.dispatchOneTask(taskId)}
     *       (skips the gate since the assignee is approved). The task row stays
     *       PENDING until that call, then transitions PENDING -> IN_PROGRESS ->
     *       terminal exactly as on the normal worker-loop path.</li>
     *   <li><b>approve=false</b> — operator rejected this task's dispatch. Flip the
     *       task directly to BLOCKED with a "Rejected by operator" reason; the
     *       worker loop will cascade BLOCKED to any dependents that referenced it.</li>
     * </ul>
     */
    private void resumeTasksDispatch(HumanReviewPending pending, Cursor cursor, boolean approve) {
        if (cursor.taskId == null) {
            log.warn("TeamMemberDispatchResumeHandler: TASKS cursor missing taskId for pending {}; no-op",
                    pending.getId());
            return;
        }
        TaskRepository taskRepository = applicationContext.getBean(TaskRepository.class);
        TaskEntity task = taskRepository.findByIdAndOrgId(cursor.taskId, pending.getOrgId()).orElse(null);
        if (task == null) {
            log.warn("TeamMemberDispatchResumeHandler: task {} not found (org={}) for pending {}; no-op",
                    cursor.taskId, pending.getOrgId(), pending.getId());
            return;
        }
        if (!approve) {
            task.setStatus(TaskStatus.BLOCKED);
            task.setResult("Rejected by operator on HITL pause");
            taskRepository.save(task);
            log.info("TeamMemberDispatchResumeHandler: TASKS rejection marked task {} BLOCKED", task.getId());
            return;
        }
        TasksOrchestrator orchestrator = applicationContext.getBean(TasksOrchestrator.class);
        AgentOperations runner = applicationContext.getBean(AgentOperations.class);
        try {
            ScopedValue
                    .where(AgentContextHolder.currentRunId, pending.getRunId())
                    .where(AgentContextHolder.orgId, pending.getOrgId())
                    .where(AgentContextHolder.userId, cursor.userId)
                    .where(AgentContextHolder.sessionId, cursor.sessionId)
                    .where(AgentContextHolder.approvedTeamMembers, Set.of(cursor.memberAgentId))
                    .where(AgentContextHolder.orchestrationDepth, 1)
                    .run(() -> orchestrator.dispatchOneTask(task.getId(), pending.getOrgId(), runner,
                            cursor.userId, cursor.sessionId));
        } catch (RuntimeException ex) {
            log.error("TeamMemberDispatchResumeHandler: TASKS resume threw for task {} (pending {}): {}",
                    task.getId(), pending.getId(), ex.toString(), ex);
        }
    }

    private void executeResume(HumanReviewPending pending, Cursor cursor, int startIndex,
                               Set<String> approvedTeamMembers) {
        String teamRunId = pending.getRunId();
        // RunRepository inherits findById from both RunOperations and CrudRepository — cast disambiguates.
        AgentRun teamRun = ((org.springframework.data.repository.CrudRepository<AgentRun, String>) runRepository)
                .findById(teamRunId).orElse(null);
        if (teamRun == null) {
            log.warn("TeamMemberDispatchResumeHandler: team run {} not found for pending {}; no-op",
                    teamRunId, pending.getId());
            return;
        }
        String orgId = teamRun.getOrgId();
        String teamId = teamRun.getAgentId();

        AgentRegistry agentRegistry = applicationContext.getBean(AgentRegistry.class);
        SequentialOrchestrator orchestrator = applicationContext.getBean(SequentialOrchestrator.class);
        AgentOperations runner = applicationContext.getBean(AgentOperations.class);
        AgentRunFinalizer finalizer = applicationContext.getBean(AgentRunFinalizer.class);

        AgentDefinition team = agentRegistry.findById(teamId, orgId);
        if (team == null) {
            log.warn("TeamMemberDispatchResumeHandler: team {} not resolvable (orgId={}); cannot resume pending {}",
                    teamId, orgId, pending.getId());
            return;
        }

        log.info("TeamMemberDispatchResumeHandler: resuming team run {} at index {} (member={}, approvedSet={})",
                teamRunId, startIndex, cursor.memberAgentId, approvedTeamMembers);

        try {
            String result = ScopedValue
                    .where(AgentContextHolder.currentRunId, teamRunId)
                    .where(AgentContextHolder.orgId, orgId)
                    .where(AgentContextHolder.userId, cursor.userId)
                    .where(AgentContextHolder.sessionId, cursor.sessionId)
                    .where(AgentContextHolder.approvedTeamMembers, approvedTeamMembers)
                    // orchestrationDepth=1 — signals to AgentService.ensureSessionExists
                    // that inner member runs are dispatched by a team orchestrator, which
                    // bypasses the agent_id-mismatch guard for the team→member session reuse.
                    // Matches the binding the live TeamOrchestrationEngine sets around
                    // orchestrator.execute on a fresh team run.
                    .where(AgentContextHolder.orchestrationDepth, 1)
                    .call(() -> orchestrator.resumeAt(team, startIndex, cursor.currentInput,
                            null, cursor.sessionId, cursor.userId, orgId, runner));
            finalizer.finalizeRun(teamRunId, RunStatus.COMPLETED, result, null, null);
        } catch (RuntimeException ex) {
            log.error("TeamMemberDispatchResumeHandler: resume threw for team run {} (pending {}): {}",
                    teamRunId, pending.getId(), ex.toString(), ex);
            finalizer.finalizeRun(teamRunId, RunStatus.FAILED,
                    "Team resume failed: " + ex.getMessage(), null, null);
        } catch (Exception ex) {
            // ScopedValue.call declares Exception; in practice the orchestrator only throws RuntimeException.
            log.error("TeamMemberDispatchResumeHandler: resume threw checked exception (unexpected) for pending {}: {}",
                    pending.getId(), ex.toString(), ex);
            finalizer.finalizeRun(teamRunId, RunStatus.FAILED,
                    "Team resume failed: " + ex.getMessage(), null, null);
        }
    }

    private void finalizeCancel(String teamRunId, String reason) {
        AgentRunFinalizer finalizer = applicationContext.getBean(AgentRunFinalizer.class);
        log.info("TeamMemberDispatchResumeHandler: cancelling team run {} ({})", teamRunId, reason);
        finalizer.finalizeRun(teamRunId, RunStatus.CANCELLED, reason, null, null);
    }

    private static Cursor readCursor(Map<String, Object> opts) {
        Object memberId = opts.get(TeamMemberDispatchExtraOptions.MEMBER_AGENT_ID);
        Object strategy = opts.get(TeamMemberDispatchExtraOptions.STRATEGY);
        if (memberId == null || strategy == null) {
            return null;
        }
        Cursor c = new Cursor();
        c.strategy = strategy.toString();
        c.memberAgentId = memberId.toString();
        c.currentInput = asString(opts.get(TeamMemberDispatchExtraOptions.CURRENT_INPUT));
        c.sessionId = asString(opts.get(TeamMemberDispatchExtraOptions.SESSION_ID));
        c.userId = asString(opts.get(TeamMemberDispatchExtraOptions.USER_ID));
        c.taskId = asString(opts.get(TeamMemberDispatchExtraOptions.TASK_ID));
        c.planRaw = opts.get(TeamMemberDispatchExtraOptions.PLAN);
        // memberIndex is required for SEQUENTIAL but optional for TASKS.
        Object idxRaw = opts.get(TeamMemberDispatchExtraOptions.MEMBER_INDEX);
        if (idxRaw != null) {
            c.memberIndex = idxRaw instanceof Number n ? n.intValue() : Integer.parseInt(idxRaw.toString());
        } else if ("SEQUENTIAL".equals(c.strategy) || "PLANNER".equals(c.strategy)) {
            return null; // SEQUENTIAL/PLANNER must have memberIndex; missing key = malformed cursor.
        }
        return c;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static OnRejectPolicy readOnRejectPolicy(Map<String, Object> opts) {
        Object raw = opts.get("onReject");
        if (raw == null) return OnRejectPolicy.SKIP;
        try {
            return OnRejectPolicy.valueOf(raw.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OnRejectPolicy.SKIP;
        }
    }

    private static final class Cursor {
        String strategy;
        String memberAgentId;
        int memberIndex;
        String currentInput;
        String sessionId;
        String userId;
        /** REQ-TT-6 — populated for strategy=TASKS only. */
        String taskId;
        /** Populated for strategy=PLANNER only. Raw Object so decoding happens lazily in the
         *  PLANNER branch; everyone else ignores it. */
        Object planRaw;
    }
}
