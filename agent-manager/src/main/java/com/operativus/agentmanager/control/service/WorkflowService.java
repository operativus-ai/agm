package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.Workflow;
import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.core.entity.WorkflowEdge;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.core.model.workflow.DagFrontier;
import com.operativus.agentmanager.core.model.workflow.PauseKind;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.WorkflowDTO;
import com.operativus.agentmanager.core.model.WorkflowStepDTO;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.control.repository.WorkflowStepRepository;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.WorkflowConstants;
import com.operativus.agentmanager.core.entity.WorkflowRun;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.model.enums.StepActionType;

import com.operativus.agentmanager.control.websocket.WorkflowWebSocketHandler;



/**
 * Domain Responsibility: Manages the definition and sequential execution of multi-agent Workflows.
 * State: Stateless externally (Execution state is managed via persistable WorkflowRun entities and Virtual Threads)
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    /**
     * Synthetic user_id stamped on agent_sessions rows that the workflow Run path
     * auto-creates when the request supplies a fresh sessionId. Matches the scheduler's
     * "sched-user" convention so audit/observability can recognize machine-spawned sessions.
     */
    public static final String WORKFLOW_RUN_SYNTHETIC_USER_ID = "workflow-run-user";

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final AgentRepository agentRepository;
    private final SessionRepository sessionRepository;
    private final WorkflowWebSocketHandler webSocketHandler;
    private final com.operativus.agentmanager.core.registry.AgentOperations agentOperations;
    private final Tracer tracer;
    private final List<com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension> stepExecutorExtensions;
    private final MeterRegistry meterRegistry;
    private final java.util.Map<com.operativus.agentmanager.core.model.enums.RouteSelectorType,
            com.operativus.agentmanager.core.spi.RouteSelector> selectorByType;
    private final com.operativus.agentmanager.compute.routing.ConditionEvaluator conditionEvaluator;
    private final HumanReviewService humanReviewService;
    private final com.operativus.agentmanager.control.repository.WorkflowEdgeRepository workflowEdgeRepository;
    private final com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor dagWorkflowExecutor;

    /**
     * REQ-DR-5 DAG engine flag — default ON. When {@code true} AND the workflow has explicit
     * {@code workflow_edges}, {@link #executeWorkflowAsync} delegates dispatch to the
     * {@link com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor} frontier
     * scheduler (real fan-out/fan-in along the edges); a workflow with no edges always takes the
     * flat {@code step_order} loop, so drawing edges is what opts a workflow in. {@code false} is
     * an emergency opt-out for NEW dispatches only — resume routing keys off the persisted
     * {@code dag_frontier}, never this flag (see {@link #isDagRun}).
     */
    @Value("${agm.workflow.dag.enabled:true}")
    private boolean dagEnabled;

    @Value("${agentmanager.scheduler.workflow-run-paused-cutoff-hours:24}")
    private long workflowRunPausedCutoffHours;

    @Value("${agentmanager.scheduler.workflow-run-running-cutoff-hours:2}")
    private long workflowRunRunningCutoffHours;

    /**
     * REQ-DR-4 (Workflow Router step) feature flag. When {@code false} (default),
     * any {@code action=ROUTER} workflow step demotes to AGENT behavior so the
     * column is durable but inert — matches the {@code StepActionType.ROUTER}
     * docstring fallback contract. Flip to {@code true} per-environment to
     * activate the selector-driven dispatch.
     */
    @Value("${agm.workflow.router.enabled:false}")
    private boolean routerEnabled;

    public WorkflowService(WorkflowRepository workflowRepository,
                           WorkflowStepRepository workflowStepRepository,
                           WorkflowRunRepository workflowRunRepository,
                           AgentRepository agentRepository,
                           SessionRepository sessionRepository,
                           WorkflowWebSocketHandler webSocketHandler,
                           com.operativus.agentmanager.core.registry.AgentOperations agentOperations,
                           Tracer tracer,
                           List<com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension> stepExecutorExtensions,
                           MeterRegistry meterRegistry,
                           List<com.operativus.agentmanager.core.spi.RouteSelector> routeSelectors,
                           com.operativus.agentmanager.compute.routing.ConditionEvaluator conditionEvaluator,
                           HumanReviewService humanReviewService,
                           com.operativus.agentmanager.control.repository.WorkflowEdgeRepository workflowEdgeRepository,
                           com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor dagWorkflowExecutor) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.webSocketHandler = webSocketHandler;
        this.agentOperations = agentOperations;
        this.tracer = tracer;
        this.stepExecutorExtensions = stepExecutorExtensions != null ? stepExecutorExtensions : java.util.Collections.emptyList();
        this.meterRegistry = meterRegistry;
        List<com.operativus.agentmanager.core.spi.RouteSelector> selectors =
                routeSelectors != null ? routeSelectors : java.util.Collections.emptyList();
        this.selectorByType = selectors.stream().collect(java.util.stream.Collectors.toMap(
                com.operativus.agentmanager.core.spi.RouteSelector::selectorType,
                java.util.function.Function.identity(),
                (a, b) -> a));
        this.conditionEvaluator = conditionEvaluator;
        this.humanReviewService = humanReviewService;
        this.workflowEdgeRepository = workflowEdgeRepository;
        this.dagWorkflowExecutor = dagWorkflowExecutor;
    }

    /**
     * @summary Retrieves all defined workflow templates owned by the caller's org.
     */
    public List<WorkflowDTO> getAllWorkflows() {
        return withStepCounts(workflowRepository.findAllByOrgId(callerOrgId(),
                        org.springframework.data.domain.Pageable.unpaged()))
                .getContent();
    }

    /**
     * @summary Retrieves a paginated set of workflow templates owned by the caller's org.
     */
    public org.springframework.data.domain.Page<WorkflowDTO> getAllWorkflows(org.springframework.data.domain.Pageable pageable) {
        return withStepCounts(workflowRepository.findAllByOrgId(callerOrgId(), pageable));
    }

    /**
     * Maps a page of workflows to DTOs, resolving the per-workflow step count via a single
     * batch GROUP BY query (not an N+1 of {@code countByWorkflowId}). Workflows with no steps
     * are absent from the count result and default to 0.
     */
    private org.springframework.data.domain.Page<WorkflowDTO> withStepCounts(
            org.springframework.data.domain.Page<Workflow> page) {
        java.util.List<String> ids = page.getContent().stream().map(Workflow::getId).toList();
        java.util.Map<String, Integer> counts = ids.isEmpty() ? java.util.Map.of()
                : workflowStepRepository.countByWorkflowIdIn(ids).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                WorkflowStepRepository.WorkflowStepCount::getWorkflowId,
                                c -> (int) c.getCount()));
        return page.map(w -> toDto(w, counts.getOrDefault(w.getId(), 0)));
    }

    /**
     * @summary Retrieves a specific workflow template by its unique ID, scoped to caller's org.
     */
    public Optional<WorkflowDTO> getWorkflowById(String id) {
        return workflowRepository.findByIdAndOrgId(id, callerOrgId()).map(this::toDto);
    }

    /**
     * @summary Creates a new workflow template definition with caller's orgId stamped.
     * @logic Uses the provided ID or generates a new UUID, instantiates a Workflow entity, sets
     *        orgId from {@code AgentContextHolder.getOrgId()} (with DEFAULT_SYSTEM_ORG fallback),
     *        and persists. Body-injected orgId is ignored via @JsonProperty(READ_ONLY) on the
     *        entity field.
     */
    @Transactional
    public WorkflowDTO createWorkflow(WorkflowDTO workflowDTO) {
        String id = workflowDTO.id() != null ? workflowDTO.id() : UUID.randomUUID().toString();
        Workflow workflow = new Workflow(id, workflowDTO.name(), workflowDTO.description());
        workflow.setOrgId(callerOrgId());
        Workflow saved = workflowRepository.save(workflow);
        return toDto(saved);
    }

    /**
     * @summary Updates an existing workflow template definition, scoped to caller's org.
     * @logic Cross-tenant updates throw {@code IllegalArgumentException} (mapped to 404 by the
     *        controller) — same as missing-id behavior, no existence-leak.
     */
    @Transactional
    public WorkflowDTO updateWorkflow(String id, WorkflowDTO workflowDTO) {
        return workflowRepository.findByIdAndOrgId(id, callerOrgId()).map(existing -> {
            if (workflowDTO.name() != null) existing.setName(workflowDTO.name());
            if (workflowDTO.description() != null) existing.setDescription(workflowDTO.description());
            // orgId is immutable post-create; body cannot rewrite tenant.
            Workflow updated = workflowRepository.save(existing);
            return toDto(updated);
        }).orElseThrow(() -> new IllegalArgumentException("Workflow not found for ID: " + id));
    }

    /**
     * @summary Deletes a workflow template by ID, scoped to caller's org. Cross-tenant is no-op.
     * @logic Controller returns 204 unconditionally; service-level guard prevents the delete
     *        from running across tenants. DB cascade still removes child WorkflowSteps for the
     *        owned row.
     */
    @Transactional
    public void deleteWorkflow(String id) {
        if (workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            workflowRepository.deleteById(id);
        }
    }

    /**
     * @summary Retrieves all chronological steps associated with a specific workflow.
     * @logic Cross-tenant lookups return an empty list (the parent workflow is invisible to
     *        the caller). System-context callers within this same service traverse via
     *        unscoped {@code workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc}.
     */
    public List<WorkflowStepDTO> getWorkflowSteps(String workflowId) {
        if (!workflowRepository.existsByIdAndOrgId(workflowId, callerOrgId())) {
            return List.of();
        }
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId).stream()
                .map(this::toStepDto)
                .collect(Collectors.toList());
    }

    /**
     * @summary Adds a new executable step to a workflow definition, scoped to caller's org.
     * @throws IllegalArgumentException when the parent workflow is not in caller's tenant
     *         (mapped by callers to a 404 — no existence-leak). Same shape as cross-tenant
     *         {@code cloneWorkflow}.
     * @throws IllegalArgumentException when the step is a ROUTER (REQ-DR-4) and its
     *         {@code routerConfig} is missing required fields (selectorType, non-empty
     *         choices map). Non-ROUTER steps with a non-null routerConfig are rejected
     *         to keep the column meaningful per action type.
     */
    @Transactional
    public WorkflowStepDTO addWorkflowStep(String workflowId, WorkflowStepDTO stepDTO) {
        if (!workflowRepository.existsByIdAndOrgId(workflowId, callerOrgId())) {
            throw new IllegalArgumentException("Workflow not found for ID: " + workflowId);
        }
        validateStepAgentId(stepDTO.action(), stepDTO.agentId());
        validateRouterConfig(stepDTO);
        validateOnReject(stepDTO);
        validateElseStepId(workflowId, stepDTO);
        validateRequiresConfirmation(stepDTO);
        String id = stepDTO.id() != null ? stepDTO.id() : UUID.randomUUID().toString();
        WorkflowStep step = new WorkflowStep(id, workflowId, stepDTO.stepOrder(), stepDTO.agentId(),
                stepDTO.action(), stepDTO.routerConfig(), stepDTO.onReject(), stepDTO.elseStepId(),
                Boolean.TRUE.equals(stepDTO.requiresConfirmation()));
        WorkflowStep saved = workflowStepRepository.save(step);
        return toStepDto(saved);
    }

    /**
     * @summary Updates the editable config of an existing workflow step (REQ-DR-5 node editing):
     *          the agent / expression ({@code agentId}), order, and CONDITION/ROUTER config. The
     *          step's {@code action} (node kind) is immutable here — changing a node's kind mid-graph
     *          is a separate concern. ADMIN-only; org-scoped; cross-workflow step ids are rejected.
     * @logic Reuses the same field validations as {@link #addWorkflowStep}, evaluated against the
     *        step's existing (immutable) action.
     */
    public WorkflowStepDTO updateWorkflowStep(String workflowId, String stepId, WorkflowStepDTO stepDTO) {
        if (!workflowRepository.existsByIdAndOrgId(workflowId, callerOrgId())) {
            throw new IllegalArgumentException("Workflow not found for ID: " + workflowId);
        }
        WorkflowStep step = workflowStepRepository.findById(stepId)
                .filter(s -> workflowId.equals(s.getWorkflowId()))
                .orElseThrow(() -> new IllegalArgumentException("Step not found for ID: " + stepId));

        String action = step.getAction(); // immutable on update
        WorkflowStepDTO effective = new WorkflowStepDTO(stepId, workflowId, stepDTO.stepOrder(),
                stepDTO.agentId(), action, stepDTO.routerConfig(), stepDTO.onReject(), stepDTO.elseStepId(),
                stepDTO.requiresConfirmation(), null, null, null);

        validateStepAgentId(action, effective.agentId());
        validateRouterConfig(effective);
        validateOnReject(effective);
        validateElseStepId(workflowId, effective);
        validateRequiresConfirmation(effective);

        if (stepDTO.stepOrder() != null) step.setStepOrder(stepDTO.stepOrder());
        step.setAgentId(stepDTO.agentId());
        step.setRouterConfig(stepDTO.routerConfig());
        step.setOnReject(stepDTO.onReject());
        step.setElseStepId(stepDTO.elseStepId());
        step.setRequiresConfirmation(Boolean.TRUE.equals(stepDTO.requiresConfirmation()));
        return toStepDto(workflowStepRepository.save(step));
    }

    /**
     * Kind-aware validation of the {@code agent_id} column's per-kind payload (shared by step
     * add + update). The column is overloaded by convention:
     * <ul>
     *   <li>CONDITION / LOOP — predicate / bounds expression; FUNCTION — registered function key;
     *       WEBHOOK — outbound URL or extension key. None of these are agent ids — checking them
     *       against the agent registry would always 404.</li>
     *   <li>WORKFLOW — a child workflow id, validated org-scoped against the workflow repo
     *       (cross-tenant reads as not-found, §79).</li>
     *   <li>Everything else (AGENT / SEQUENTIAL / PARALLEL / ROUTER / JOIN) — an agent id when
     *       present (PARALLEL and JOIN are structural gates on the DAG path and may omit it).</li>
     * </ul>
     */
    private void validateStepAgentId(String action, String agentId) {
        if (agentId == null) return;
        var actionType = com.operativus.agentmanager.core.model.enums.StepActionType.fromString(action);
        switch (actionType) {
            case CONDITION, LOOP, FUNCTION, WEBHOOK -> { /* expression / key / URL — not an agent id */ }
            case WORKFLOW -> {
                if (!workflowRepository.existsByIdAndOrgId(agentId, callerOrgId())) {
                    throw new IllegalArgumentException("Sub-workflow not found for ID: " + agentId);
                }
            }
            default -> {
                if (!agentRepository.existsByIdAndOrgId(agentId, callerOrgId())) {
                    throw new IllegalArgumentException("Agent not found for ID: " + agentId);
                }
            }
        }
    }

    /**
     * REQ-DR-6 PR-2 — validates the {@code onReject} policy:
     * <ul>
     *   <li>null is always valid (defaults to SKIP at dispatch time)</li>
     *   <li>"SKIP" / "CANCEL" are valid on CONDITION steps</li>
     *   <li>any non-null value on a non-CONDITION step is rejected</li>
     *   <li>unknown enum value (e.g. "ELSE_BRANCH" before that PR lands) is rejected loudly</li>
     * </ul>
     */
    private void validateOnReject(WorkflowStepDTO stepDTO) {
        String onReject = stepDTO.onReject();
        if (onReject == null) return;
        var actionType = com.operativus.agentmanager.core.model.enums.StepActionType.fromString(stepDTO.action());
        if (actionType != com.operativus.agentmanager.core.model.enums.StepActionType.CONDITION) {
            throw new IllegalArgumentException("onReject is only valid for CONDITION steps");
        }
        try {
            com.operativus.agentmanager.core.model.enums.OnRejectPolicy.valueOf(onReject.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown onReject policy: " + onReject
                    + " (supported: SKIP, CANCEL)");
        }
    }

    /**
     * REQ-DR-6 PR-3 — validates {@code elseStepId} placement:
     * <ul>
     *   <li>null is always valid</li>
     *   <li>non-null only allowed when {@code onReject == ELSE_BRANCH}</li>
     *   <li>target step must belong to the same workflow (cross-workflow leak
     *       protection via existsByIdAndWorkflowId)</li>
     *   <li>{@code ELSE_BRANCH} without {@code elseStepId} is rejected</li>
     * </ul>
     */
    /**
     * REQ-DR-6 PR-4 — validates the {@code requiresConfirmation} placement:
     * <ul>
     *   <li>null / false is always valid</li>
     *   <li>true only allowed on CONDITION steps</li>
     *   <li>true disallowed in combination with {@code on_reject=CANCEL} —
     *       cancellation is destructive and an "approved cancel" is just a
     *       cancel; operator should reject by cancelling the run directly.</li>
     * </ul>
     */
    private void validateRequiresConfirmation(WorkflowStepDTO stepDTO) {
        if (!Boolean.TRUE.equals(stepDTO.requiresConfirmation())) return;
        var actionType = com.operativus.agentmanager.core.model.enums.StepActionType.fromString(stepDTO.action());
        if (actionType != com.operativus.agentmanager.core.model.enums.StepActionType.CONDITION) {
            throw new IllegalArgumentException("requiresConfirmation is only valid for CONDITION steps");
        }
        var policy = com.operativus.agentmanager.core.model.enums.OnRejectPolicy.fromString(stepDTO.onReject());
        if (policy == com.operativus.agentmanager.core.model.enums.OnRejectPolicy.CANCEL) {
            throw new IllegalArgumentException(
                    "requiresConfirmation is not allowed with onReject=CANCEL — cancel the run directly");
        }
    }

    private void validateElseStepId(String workflowId, WorkflowStepDTO stepDTO) {
        var policy = com.operativus.agentmanager.core.model.enums.OnRejectPolicy.fromString(stepDTO.onReject());
        boolean wantsElse = policy == com.operativus.agentmanager.core.model.enums.OnRejectPolicy.ELSE_BRANCH;

        if (wantsElse && (stepDTO.elseStepId() == null || stepDTO.elseStepId().isBlank())) {
            throw new IllegalArgumentException("onReject=ELSE_BRANCH requires elseStepId");
        }
        if (!wantsElse && stepDTO.elseStepId() != null) {
            throw new IllegalArgumentException(
                    "elseStepId is only valid when onReject=ELSE_BRANCH");
        }
        if (wantsElse) {
            boolean targetInSameWorkflow = workflowStepRepository.findById(stepDTO.elseStepId())
                    .map(s -> workflowId.equals(s.getWorkflowId()))
                    .orElse(false);
            if (!targetInSameWorkflow) {
                throw new IllegalArgumentException(
                        "elseStepId '" + stepDTO.elseStepId() + "' must reference a step in the same workflow");
            }
        }
    }

    private void validateRouterConfig(WorkflowStepDTO stepDTO) {
        var actionType = com.operativus.agentmanager.core.model.enums.StepActionType.fromString(stepDTO.action());
        boolean isRouter = actionType == com.operativus.agentmanager.core.model.enums.StepActionType.ROUTER;
        var cfg = stepDTO.routerConfig();
        if (!isRouter) {
            if (cfg != null) {
                throw new IllegalArgumentException("routerConfig is only valid for ROUTER steps");
            }
            return;
        }
        if (cfg == null) {
            throw new IllegalArgumentException("ROUTER step requires routerConfig");
        }
        if (cfg.selectorType() == null) {
            throw new IllegalArgumentException("ROUTER step requires routerConfig.selectorType");
        }
        if (cfg.choices() == null || cfg.choices().isEmpty()) {
            throw new IllegalArgumentException("ROUTER step requires routerConfig.choices (non-empty map)");
        }
    }

    /**
     * @summary Removes a specific step from a workflow, scoped via the parent workflow's tenant.
     * @logic Cross-tenant deletes are no-ops. Resolves the step → its workflowId → the parent
     *        workflow's orgId; only fires the delete when the parent belongs to the caller.
     */
    @Transactional
    public void deleteWorkflowStep(String stepId) {
        workflowStepRepository.findById(stepId).ifPresent(step -> {
            if (workflowRepository.existsByIdAndOrgId(step.getWorkflowId(), callerOrgId())) {
                workflowStepRepository.deleteById(stepId);
            }
        });
    }

    /**
     * @summary Deep-clones a workflow (and its steps) within the caller's org. Cross-tenant is 404.
     */
    @Transactional
    public WorkflowDTO cloneWorkflow(String sourceWorkflowId) {
        Workflow source = workflowRepository.findByIdAndOrgId(sourceWorkflowId, callerOrgId())
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found for ID: " + sourceWorkflowId));

        String clonedId = UUID.randomUUID().toString();
        Workflow cloned = new Workflow(clonedId, source.getName() + " (Copy)", source.getDescription());
        cloned.setOrgId(callerOrgId());
        workflowRepository.save(cloned);

        List<WorkflowStep> sourceSteps = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(sourceWorkflowId);
        for (WorkflowStep sourceStep : sourceSteps) {
            WorkflowStep clonedStep = new WorkflowStep(
                    UUID.randomUUID().toString(),
                    clonedId,
                    sourceStep.getStepOrder(),
                    sourceStep.getAgentId(),
                    sourceStep.getAction()
            );
            workflowStepRepository.save(clonedStep);
        }

        log.info("Cloned workflow {} -> {} ({} steps)", sourceWorkflowId, clonedId, sourceSteps.size());
        return toDto(cloned);
    }

    /**
     * Resolves the caller's {@code orgId} from {@link AgentContextHolder}, falling back to
     * {@link TenantConstants#DEFAULT_SYSTEM_ORG} when no auth context is present (system
     * background callers). Mirrors the pattern in {@code KnowledgeBaseController.callerOrgId}
     * and {@code ScheduleService.callerOrgId}.
     */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }

    // --- Stuck-Run Sweepers ---

    /**
     * On startup, cancel any workflow_runs stuck in RUNNING longer than {@code workflow-run-running-cutoff-hours}.
     * Handles orphaned runs left by a crash or ungraceful restart — the VT is gone but the DB row
     * still shows RUNNING. Any run younger than the cutoff is left alone; it may belong to a
     * legitimate in-flight job that survived the restart via the persistent queue.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cancelOrphanedRunningWorkflowRuns() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(workflowRunRunningCutoffHours);
        java.util.List<WorkflowRun> orphaned = workflowRunRepository.findByStatusInAndCreatedAtBefore(
                java.util.List.of(RunStatus.RUNNING), cutoff);
        if (orphaned.isEmpty()) {
            log.info("Startup scan: no orphaned RUNNING workflow_runs older than {}h.", workflowRunRunningCutoffHours);
            return;
        }
        log.warn("Startup scan: cancelling {} orphaned RUNNING workflow_run(s) older than {}h.",
                orphaned.size(), workflowRunRunningCutoffHours);
        for (WorkflowRun run : orphaned) {
            run.setStatus(RunStatus.CANCELLED);
            run.setCurrentPayload("Cancelled: execution orphaned by application restart.");
            run.setUpdatedAt(java.time.LocalDateTime.now());
            workflowRunRepository.save(run);
            meterRegistry.counter("agm.workflow_runs.stuck_running_cancelled_total").increment();
        }
    }

    /**
     * Hourly sweep: cancel workflow_runs stuck in PAUSED beyond the cutoff. Mirrors
     * {@code RunExecutionManager.expireStuckPausedRuns} for the workflow-run side. A run is
     * "stuck PAUSED" when the human approval was never submitted (user abandoned the session).
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.workflow-run-paused-cleanup-ms:3600000}")
    public void expireStuckPausedWorkflowRuns() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(workflowRunPausedCutoffHours);
        java.util.List<WorkflowRun> stuck = workflowRunRepository.findByStatusInAndCreatedAtBefore(
                java.util.List.of(RunStatus.PAUSED), cutoff);
        if (stuck.isEmpty()) {
            log.debug("Stuck-PAUSED workflow sweep: no PAUSED workflow_runs older than {}h.", workflowRunPausedCutoffHours);
            return;
        }
        log.warn("Stuck-PAUSED workflow sweep: cancelling {} workflow_run(s) PAUSED >= {}h.",
                stuck.size(), workflowRunPausedCutoffHours);
        for (WorkflowRun run : stuck) {
            run.setStatus(RunStatus.CANCELLED);
            run.setCurrentPayload("Cancelled: workflow run stuck in PAUSED state beyond "
                    + workflowRunPausedCutoffHours + "h cutoff.");
            run.setUpdatedAt(java.time.LocalDateTime.now());
            workflowRunRepository.save(run);
            webSocketHandler.broadcastEvent(run.getWorkflowId(), "RunCancelled",
                    Map.of("runId", run.getId(), "reason", "STUCK_PAUSED_EXPIRY"));
            meterRegistry.counter("agm.workflow_runs.stuck_paused_cancelled_total").increment();
        }
    }

    // --- Execution Engine ---

    /**
     * @summary Executes a sequential multi-agent workflow asynchronously in a Virtual Thread.
     * @logic Captures the current Micrometer observation context for tracing, wraps the execution block in a Runnable task, broadcasts 'RunStarted' to WebSocket clients, iterates over workflow steps sequentially, synchronously invokes `AgentService.run()` for each step, chaining the output as the next step's input, halts execution and broadcasts 'RunPaused' if Human-in-the-Loop (HITL) approval is required, broadcasts 'StepCompleted', 'RunCompleted', or 'RunFailed' lifecycle events, and submits the task to a Spring Virtual Thread Executor.
     */
    public String executeWorkflowAsync(String workflowId, String initialInput, String sessionId) {
        log.info("Starting background execution for Workflow: {}, Session: {}", workflowId, sessionId);

        String runId = UUID.randomUUID().toString();
        // Resolve orgId from the workflow entity — job handlers run without an auth context,
        // so callerOrgId() would return DEFAULT_SYSTEM_ORG here; the workflow row is authoritative.
        String runOrgId = workflowRepository.findById(workflowId)
                .map(com.operativus.agentmanager.core.entity.Workflow::getOrgId)
                .orElse(TenantConstants.DEFAULT_SYSTEM_ORG);
        // POST /workflows/{id}/run can supply a fresh UUID for sessionId (default when the
        // request body omits it). The workflow_runs.session_id FK to agent_sessions then
        // aborts this insert and silently leaves the schedule in FAILED. Mirror the
        // ScheduleExecutionPoller.autoCreateSchedulerSession contract here so the ad-hoc
        // Run path is symmetric with the scheduler path.
        ensureSessionExists(sessionId, runOrgId);
        WorkflowRun workflowRun = new WorkflowRun(runId, workflowId, sessionId, RunStatus.RUNNING, 1, initialInput, runOrgId);
        workflowRunRepository.save(workflowRun);

        // F2 — fresh VTs spawned by Executors.newVirtualThreadPerTaskExecutor() do NOT inherit
        // JDK 21 ScopedValues. Capture the caller thread's tenant/user context before fan-out
        // and rebind on the worker via AgentContextSnapshot. We also explicitly bind a fresh
        // workflowRunId array so AgentContextHolder.setWorkflowRunId(runId) below stops being
        // a silent no-op (it only writes when the ScopedValue is already bound).
        final com.operativus.agentmanager.core.callback.AgentContextSnapshot workflowSnapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        final String[] workflowRunIdHolder = new String[1];

        Runnable task = ContextSnapshotFactory.builder().build().captureAll().wrap(() -> workflowSnapshot.run(() ->
                ScopedValue.where(com.operativus.agentmanager.core.callback.AgentContextHolder.workflowRunId, workflowRunIdHolder).run(() -> {
            Span span = tracer.nextSpan().name("WorkflowService.executeWorkflowAsync").tag("workflowId", workflowId).tag("runId", runId).start();
            try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                com.operativus.agentmanager.core.callback.AgentContextHolder.setWorkflowRunId(runId);
                long workflowStartTime = System.currentTimeMillis();
                webSocketHandler.broadcastEvent(workflowId, "RunStarted", Map.of("sessionId", sessionId, "runId", runId, "input", initialInput));
                log.info("Workflow execution started: {} (Run ID: {})", workflowId, runId);

                List<WorkflowStep> steps = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId);
                String currentInput = initialInput;

                // REQ-DR-5 DAG path: when enabled AND the workflow has explicit edges, the frontier
                // scheduler walks the graph (real fan-out/fan-in) instead of the flat step_order loop.
                // Legacy edge-less workflows always take the flat loop, so this is a no-op until a
                // workflow is authored with edges in an environment that flips the flag on.
                if (dagEnabled) {
                    List<WorkflowEdge> edges = workflowEdgeRepository.findByWorkflowIdOrderByFromStepIdAsc(workflowId);
                    if (!edges.isEmpty()) {
                        runViaDag(workflowRun, steps, edges, initialInput, runId, workflowId, sessionId);
                        return;
                    }
                }

                for (int i = 0; i < steps.size(); i++) {
                    // Cooperative cancellation checkpoint. A concurrent DELETE
                    // /api/v1/workflows/runs/{runId} (or any WorkflowService.cancelWorkflowRun
                    // caller) flips the workflow_run row to CANCELLED in its own committed
                    // transaction; this poll picks it up at the next step boundary and exits
                    // before more work is dispatched. The in-flight step's agent call runs
                    // to completion (no Thread.interrupt — a prior interrupt-based prototype
                    // left zombie PG connections when cancel raced a mid-step JDBC call;
                    // see BackgroundRunsRuntimeTest's deleteBackgroundRunOnCompletedRun*
                    // doc-block for the incident).
                    if (isWorkflowRunCancelled(runId)) {
                        log.info("Workflow run {} cancelled mid-flight; aborting before step index {}", runId, i);
                        webSocketHandler.broadcastEvent(workflowId, "RunCancelled",
                                Map.of("runId", runId, "stepIndex", i));
                        return;
                    }
                    WorkflowStep step = steps.get(i);
                    StepActionType stepAction = StepActionType.fromString(step.getAction());

                    // REQ-DR-4: ROUTER demotes to AGENT when the feature flag is off
                    // or the row is missing structured config. Matches the
                    // StepActionType.ROUTER docstring fallback contract.
                    if (stepAction == StepActionType.ROUTER
                            && (!routerEnabled || step.getRouterConfig() == null)) {
                        stepAction = StepActionType.AGENT;
                    }

                    switch (stepAction) {
                    case CONDITION -> {
                        log.info("Evaluating CONDITION step: {}", step.getId());
                        webSocketHandler.broadcastEvent(workflowId, WorkflowConstants.WS_EVENT_STEP_STARTED, Map.of("stepId", step.getId(), "type", "CONDITION"));
                        boolean conditionMet = evaluateCondition(step.getAgentId(), currentInput);
                        webSocketHandler.broadcastEvent(workflowId, "StepCompleted", Map.of("stepId", step.getId(), "conditionMet", conditionMet));

                        // REQ-HR-3: when the new unified HumanReview field is set with an
                        // active pause mode, route through HumanReviewService. The handler
                        // (WorkflowStepResumeHandler) calls back into resumeWorkflowRun
                        // when the operator decides via the unified /decide endpoint.
                        // Takes precedence over the legacy requires_confirmation field;
                        // when humanReview is null the legacy DR-6 PR-4 path below runs.
                        if (step.getHumanReview() != null && step.getHumanReview().isPauseActive()) {
                            int plannedCursor = computeConditionPlannedCursor(step, steps, conditionMet, i);
                            if (plannedCursor < 0) {
                                log.warn("CONDITION step {} humanReview is active but planned cursor "
                                        + "unresolvable (likely missing elseStepId); failing run {}",
                                        step.getId(), runId);
                                workflowRun.setStatus(RunStatus.FAILED);
                                workflowRunRepository.save(workflowRun);
                                webSocketHandler.broadcastEvent(workflowId, "RunFailed",
                                        Map.of("runId", runId, "stepId", step.getId(),
                                                "reason", "CONDITION_PLAN_UNRESOLVABLE"));
                                return;
                            }
                            workflowRun.setStatus(RunStatus.AWAITING_HUMAN_REVIEW);
                            workflowRun.setCurrentStepOrder(plannedCursor);
                            workflowRun.setCurrentPayload(currentInput);
                            workflowRunRepository.save(workflowRun);
                            humanReviewService.pauseFor(
                                    com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType.WORKFLOW_STEP,
                                    step.getId(),
                                    runId,
                                    workflowRun.getOrgId() != null ? workflowRun.getOrgId()
                                            : TenantConstants.DEFAULT_SYSTEM_ORG,
                                    "CONDITION step " + step.getId() + " (conditionMet=" + conditionMet + ")",
                                    step.getHumanReview(),
                                    Map.of("conditionMet", conditionMet, "plannedCursor", plannedCursor,
                                            "priorInput", currentInput == null ? "" : currentInput),
                                    SecurityPrincipals.SYSTEM_PRINCIPAL);
                            return;
                        }

                        // REQ-DR-6 PR-4: if HITL gate is on, pause BEFORE applying the
                        // policy. Persist the planned cursor (post-policy position)
                        // so the existing resumeWorkflowRun loop picks up the right
                        // branch when the operator approves via /resume.
                        if (step.isRequiresConfirmation()) {
                            int plannedCursor = computeConditionPlannedCursor(step, steps, conditionMet, i);
                            if (plannedCursor < 0) {
                                // Couldn't resolve ELSE_BRANCH target — fail loudly rather than
                                // pause indefinitely. Mirrors the synchronous failure path.
                                log.warn("CONDITION step {} requires_confirmation=true but planned cursor "
                                        + "unresolvable (likely missing elseStepId); failing run {}",
                                        step.getId(), runId);
                                workflowRun.setStatus(RunStatus.FAILED);
                                workflowRunRepository.save(workflowRun);
                                webSocketHandler.broadcastEvent(workflowId, "RunFailed",
                                        Map.of("runId", runId, "stepId", step.getId(),
                                                "reason", "CONDITION_PLAN_UNRESOLVABLE"));
                                return;
                            }
                            workflowRun.setStatus(RunStatus.PAUSED);
                            workflowRun.setCurrentStepOrder(plannedCursor);
                            workflowRun.setCurrentPayload(currentInput);
                            workflowRunRepository.save(workflowRun);
                            webSocketHandler.broadcastEvent(workflowId, "ConditionConfirmationRequired",
                                    Map.of("runId", runId, "stepId", step.getId(),
                                            "conditionMet", conditionMet,
                                            "onReject", step.getOnReject() == null ? "SKIP" : step.getOnReject(),
                                            "plannedCursor", plannedCursor));
                            return;
                        }

                        if (!conditionMet) {
                            // REQ-DR-6 PR-2: apply on_reject policy. SKIP (default / null) preserves
                            // pre-DR-6 behavior (skip next step). CANCEL transitions the run to
                            // CANCELLED with a documented reason — useful when a condition guards
                            // an entire branch and you'd rather fail-fast than no-op forward.
                            var policy = com.operativus.agentmanager.core.model.enums.OnRejectPolicy
                                    .fromString(step.getOnReject());
                            if (policy == com.operativus.agentmanager.core.model.enums.OnRejectPolicy.CANCEL) {
                                log.info("Condition not met — on_reject=CANCEL → terminating workflow run {}", runId);
                                workflowRun.setStatus(RunStatus.CANCELLED);
                                workflowRun.setCurrentStepOrder(step.getStepOrder());
                                workflowRun.setCurrentPayload(currentInput);
                                workflowRunRepository.save(workflowRun);
                                webSocketHandler.broadcastEvent(workflowId, "RunCancelled",
                                        Map.of("runId", runId, "stepId", step.getId(),
                                                "reason", "CONDITION_REJECT_POLICY"));
                                return;
                            }
                            if (policy == com.operativus.agentmanager.core.model.enums.OnRejectPolicy.ELSE_BRANCH) {
                                // REQ-DR-6 PR-3: jump cursor to elseStepId. Mirrors ROUTER's
                                // branch-and-continue semantics — chosen step dispatches
                                // normally on the next loop iteration; subsequent steps
                                // execute linearly from there.
                                String elseStepId = step.getElseStepId();
                                int targetIdx = -1;
                                for (int j = 0; j < steps.size(); j++) {
                                    if (elseStepId != null && elseStepId.equals(steps.get(j).getId())) {
                                        targetIdx = j;
                                        break;
                                    }
                                }
                                if (targetIdx < 0) {
                                    log.warn("CONDITION step {} on_reject=ELSE_BRANCH but elseStepId '{}' not found; "
                                            + "failing run {}", step.getId(), elseStepId, runId);
                                    workflowRun.setStatus(RunStatus.FAILED);
                                    workflowRunRepository.save(workflowRun);
                                    webSocketHandler.broadcastEvent(workflowId, "RunFailed",
                                            Map.of("runId", runId, "stepId", step.getId(),
                                                    "reason", "ELSE_BRANCH_TARGET_NOT_FOUND:" + elseStepId));
                                    return;
                                }
                                webSocketHandler.broadcastEvent(workflowId, "StepCompleted",
                                        Map.of("stepId", step.getId(), "branchedTo", elseStepId,
                                                "via", "ELSE_BRANCH"));
                                i = targetIdx - 1;
                                continue;
                            }
                            if (i + 1 < steps.size()) {
                                log.info("Condition not met — skipping next step: {}", steps.get(i + 1).getId());
                                i++;
                            }
                        }
                        continue;
                    }
                    case PARALLEL -> {
                        log.info("Executing PARALLEL step group starting at: {}", step.getId());
                        webSocketHandler.broadcastEvent(workflowId, WorkflowConstants.WS_EVENT_STEP_STARTED, Map.of("stepId", step.getId(), "type", "PARALLEL"));
                        List<WorkflowStep> parallelSteps = new java.util.ArrayList<>();
                        parallelSteps.add(step);
                        int parallelOrder = step.getStepOrder();
                        while (i + 1 < steps.size() && steps.get(i + 1).getStepOrder() == parallelOrder) {
                            parallelSteps.add(steps.get(++i));
                        }

                        String parallelInput = currentInput;
                        // F3 — each CompletableFuture.supplyAsync runs on a fresh VT from the
                        // per-call executor, which does NOT inherit ScopedValues. Re-capture
                        // here (we're already inside the F2 rebind so all bindings are bound)
                        // and rebind inside each future's lambda before the agent run.
                        final com.operativus.agentmanager.core.callback.AgentContextSnapshot parallelSnapshot =
                                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
                        List<java.util.concurrent.CompletableFuture<String>> futures = parallelSteps.stream()
                                .map(ps -> java.util.concurrent.CompletableFuture.supplyAsync(
                                        () -> parallelSnapshot.call(() -> executeAgentStep(ps, workflowId, runId, sessionId, parallelInput, runOrgId)),
                                        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()))
                                .toList();

                        List<String> results = futures.stream()
                                .map(f -> { try { return f.get(300, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { return "Error: " + e.getMessage(); } })
                                .toList();
                        currentInput = String.join("\n---\n", results);
                        webSocketHandler.broadcastEvent(workflowId, "StepCompleted", Map.of("stepId", step.getId(), "parallelResults", results.size()));
                        continue;
                    }
                    case WEBHOOK -> {
                        log.info("Executing WEBHOOK step: {}", step.getId());
                        webSocketHandler.broadcastEvent(workflowId, WorkflowConstants.WS_EVENT_STEP_STARTED, Map.of("stepId", step.getId(), "type", "WEBHOOK"));
                        workflowRun.setCurrentStepOrder(step.getStepOrder());
                        workflowRun.setCurrentPayload(currentInput);
                        workflowRunRepository.save(workflowRun);

                        com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension webhookExecutor = null;
                        for (com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension ext : this.stepExecutorExtensions) {
                            if (ext.supports(step.getAgentId())) {
                                webhookExecutor = ext;
                                break;
                            }
                        }
                        if (webhookExecutor != null) {
                            currentInput = webhookExecutor.executeStep(step.getAgentId(), workflowId, runId, currentInput, Map.of("stepOrder", step.getStepOrder()));
                        } else {
                            log.warn("No SPI executor found for WEBHOOK step agentId: {}. Passing through.", step.getAgentId());
                        }
                        webSocketHandler.broadcastEvent(workflowId, "StepCompleted", Map.of("stepId", step.getId(), "output", currentInput));
                        continue;
                    }
                    case LOOP -> {
                        log.info("Executing LOOP step: {}", step.getId());
                        webSocketHandler.broadcastEvent(workflowId, WorkflowConstants.WS_EVENT_STEP_STARTED, Map.of("stepId", step.getId(), "type", "LOOP"));

                        int maxIterations = 5; // default
                        String loopExpr = step.getAgentId(); // format: "max:N|until:condition_expr"
                        String untilExpr = null;
                        if (loopExpr != null) {
                            for (String part : loopExpr.split("\\|")) {
                                String p = part.trim();
                                if (p.startsWith("max:")) {
                                    try { maxIterations = Integer.parseInt(p.substring(4).trim()); } catch (NumberFormatException ignored) {}
                                } else if (p.startsWith("until:")) {
                                    untilExpr = p.substring(6).trim();
                                }
                            }
                        }

                        // The step AFTER the LOOP is the body step to repeat
                        if (i + 1 < steps.size()) {
                            WorkflowStep bodyStep = steps.get(i + 1);
                            for (int iter = 0; iter < maxIterations; iter++) {
                                if (isWorkflowRunCancelled(runId)) {
                                    log.info("Workflow run {} cancelled mid-flight; aborting LOOP at iteration {}",
                                            runId, iter);
                                    webSocketHandler.broadcastEvent(workflowId, "RunCancelled",
                                            Map.of("runId", runId, "stepIndex", i, "iteration", iter));
                                    return;
                                }
                                log.info("LOOP iteration {}/{} — executing step: {}", iter + 1, maxIterations, bodyStep.getId());
                                webSocketHandler.broadcastEvent(workflowId, "LoopIteration", Map.of("stepId", step.getId(), "iteration", iter + 1, "maxIterations", maxIterations));
                                currentInput = executeAgentStep(bodyStep, workflowId, runId, sessionId, currentInput, runOrgId);
                                if (currentInput != null && currentInput.startsWith(WorkflowConstants.PAUSED_SENTINEL)) {
                                    workflowRun.setStatus(RunStatus.PAUSED);
                                    workflowRunRepository.save(workflowRun);
                                    webSocketHandler.broadcastEvent(workflowId, "RunPaused", Map.of("stepId", bodyStep.getId(), "reason", "HITL_APPROVAL_REQUIRED"));
                                    return;
                                }
                                // Check exit condition
                                if (untilExpr != null && evaluateCondition(untilExpr, currentInput)) {
                                    log.info("LOOP exit condition met at iteration {}", iter + 1);
                                    break;
                                }
                            }
                            i++; // Skip the body step in the main loop since we already executed it
                        }
                        webSocketHandler.broadcastEvent(workflowId, "StepCompleted", Map.of("stepId", step.getId(), "output", currentInput));
                        continue;
                    }
                    case ROUTER -> {
                        // REQ-DR-4 — selector-driven branch. Pre-screen above
                        // guarantees routerConfig != null and routerEnabled == true.
                        log.info("Executing ROUTER step: {}", step.getId());
                        webSocketHandler.broadcastEvent(workflowId, WorkflowConstants.WS_EVENT_STEP_STARTED,
                                Map.of("stepId", step.getId(), "type", "ROUTER"));

                        var routerConfig = step.getRouterConfig();
                        var selector = selectorByType.get(routerConfig.selectorType());
                        if (selector == null) {
                            log.warn("ROUTER step {}: no RouteSelector bean for type {}; failing run {}",
                                    step.getId(), routerConfig.selectorType(), runId);
                            workflowRun.setStatus(RunStatus.FAILED);
                            workflowRunRepository.save(workflowRun);
                            webSocketHandler.broadcastEvent(workflowId, "RunFailed",
                                    Map.of("runId", runId, "stepId", step.getId(),
                                            "reason", "ROUTER_SELECTOR_MISSING:" + routerConfig.selectorType()));
                            return;
                        }

                        String choiceKey = selector.selectChoice(routerConfig, currentInput);

                        if (com.operativus.agentmanager.core.spi.RouteSelector.HITL_PENDING.equals(choiceKey)) {
                            workflowRun.setStatus(RunStatus.AWAITING_ROUTE_SELECTION);
                            workflowRun.setCurrentStepOrder(step.getStepOrder());
                            workflowRun.setCurrentPayload(currentInput);
                            workflowRunRepository.save(workflowRun);
                            webSocketHandler.broadcastEvent(workflowId, "RouteSelectionPending",
                                    Map.of("runId", runId, "stepId", step.getId(),
                                            "choices", routerConfig.choices().keySet()));
                            return;
                        }

                        if (choiceKey == null) {
                            log.warn("ROUTER step {}: selector returned null and no defaultChoice; failing run {}",
                                    step.getId(), runId);
                            workflowRun.setStatus(RunStatus.FAILED);
                            workflowRunRepository.save(workflowRun);
                            webSocketHandler.broadcastEvent(workflowId, "RunFailed",
                                    Map.of("runId", runId, "stepId", step.getId(), "reason", "ROUTER_NO_MATCH"));
                            return;
                        }

                        String nextStepId = routerConfig.choices().get(choiceKey);
                        if (nextStepId == null) {
                            log.warn("ROUTER step {}: choice key '{}' not in choices map; failing run {}",
                                    step.getId(), choiceKey, runId);
                            workflowRun.setStatus(RunStatus.FAILED);
                            workflowRunRepository.save(workflowRun);
                            webSocketHandler.broadcastEvent(workflowId, "RunFailed",
                                    Map.of("runId", runId, "stepId", step.getId(),
                                            "reason", "ROUTER_INVALID_KEY:" + choiceKey));
                            return;
                        }

                        int targetIdx = -1;
                        for (int j = 0; j < steps.size(); j++) {
                            if (nextStepId.equals(steps.get(j).getId())) {
                                targetIdx = j;
                                break;
                            }
                        }
                        if (targetIdx < 0) {
                            log.warn("ROUTER step {}: target stepId '{}' not found in workflow; failing run {}",
                                    step.getId(), nextStepId, runId);
                            workflowRun.setStatus(RunStatus.FAILED);
                            workflowRunRepository.save(workflowRun);
                            webSocketHandler.broadcastEvent(workflowId, "RunFailed",
                                    Map.of("runId", runId, "stepId", step.getId(),
                                            "reason", "ROUTER_TARGET_NOT_FOUND:" + nextStepId));
                            return;
                        }

                        webSocketHandler.broadcastEvent(workflowId, "StepCompleted",
                                Map.of("stepId", step.getId(), "choiceKey", choiceKey, "branchedTo", nextStepId));
                        i = targetIdx - 1; // for-loop's i++ on next iteration lands on targetIdx
                        continue;
                    }

                    // Standard AGENT step (default)
                    default -> {
                    Span stepSpan = tracer.nextSpan().name("WorkflowService.step").tag("stepId", step.getId()).tag("agentId", step.getAgentId() != null ? step.getAgentId() : "none").start();
                    try (Tracer.SpanInScope stepWs = tracer.withSpan(stepSpan)) {
                        workflowRun.setCurrentStepOrder(step.getStepOrder());
                    workflowRun.setCurrentPayload(currentInput);
                    workflowRunRepository.save(workflowRun);

                    log.info("Executing Workflow Step: {}, Agent: {}", step.getId(), step.getAgentId());
                    long stepStartTime = System.currentTimeMillis();
                    webSocketHandler.broadcastEvent(workflowId, WorkflowConstants.WS_EVENT_STEP_STARTED, Map.of("stepId", step.getId(), "agentId", step.getAgentId()));

                    // Resolve custom step executor from Spring-managed extensions (replaces ServiceLoader SPI)
                    com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension customExecutor = null;
                    for (com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension ext : this.stepExecutorExtensions) {
                        if (ext.supports(step.getAgentId())) {
                            customExecutor = ext;
                            break;
                        }
                    }

                    if (customExecutor != null) {
                        log.info("Executing Native SPI WorkflowStepExecutorExtension: {}", step.getAgentId());
                        currentInput = customExecutor.executeStep(step.getAgentId(), workflowId, runId, currentInput, Map.of("stepOrder", step.getStepOrder()));
                    } else {
                        currentInput = executeAgentStep(step, workflowId, runId, sessionId, currentInput, runOrgId);
                        // Check for HITL pause
                        if (currentInput != null && currentInput.startsWith(WorkflowConstants.PAUSED_SENTINEL)) {
                            workflowRun.setStatus(RunStatus.PAUSED);
                            workflowRunRepository.save(workflowRun);
                            webSocketHandler.broadcastEvent(workflowId, "RunPaused", Map.of("stepId", step.getId(), "reason", "HITL_APPROVAL_REQUIRED"));
                            return;
                        }
                    }

                    long stepDuration = System.currentTimeMillis() - stepStartTime;
                    log.info("Completed Workflow Step: {}", step.getId());
                    log.debug("Workflow Step {} Duration: {}ms", step.getId(), stepDuration);
                    webSocketHandler.broadcastEvent(workflowId, "StepCompleted", Map.of("stepId", step.getId(), "output", currentInput));
                    } catch (Exception e) {
                        stepSpan.error(e);
                        throw e;
                    } finally {
                        stepSpan.end();
                    }
                    } // end default case
                    } // end switch
                }

                long totalDuration = System.currentTimeMillis() - workflowStartTime;
                log.info("Workflow execution completed: {}", workflowId);
                log.debug("Total Workflow {} Duration: {}ms", workflowId, totalDuration);
                
                workflowRun.setStatus(RunStatus.COMPLETED);
                workflowRun.setCurrentPayload(currentInput);
                workflowRunRepository.save(workflowRun);
                webSocketHandler.broadcastEvent(workflowId, "RunCompleted", Map.of("sessionId", sessionId, "finalOutput", currentInput));

            } catch (Exception e) {
                log.error("Workflow execution failed: {}", workflowId, e);
                span.error(e);
                workflowRun.setStatus(RunStatus.FAILED);
                workflowRunRepository.save(workflowRun);
                webSocketHandler.broadcastEvent(workflowId, "RunFailed", Map.of("error", e.getMessage()));
            } finally {
                com.operativus.agentmanager.core.callback.AgentContextHolder.clear();
                span.end();
            }
        })));

        // Use Virtual Threads for highly-concurrent blocking workflows
        Executors.newVirtualThreadPerTaskExecutor().submit(task);
        return runId;
    }

    /**
     * @summary Returns the current status of a workflow run, or empty if not found.
     * @logic Used by ScheduleExecutionPoller to back-propagate terminal workflow status
     *        to the parent schedule_run row without crossing module boundaries.
     */
    public java.util.Optional<RunStatus> getWorkflowRunStatus(String runId) {
        return workflowRunRepository.findById(runId).map(WorkflowRun::getStatus);
    }

    /**
     * @summary REQ-DR-4 HITL route selection — resumes a workflow run from
     *          {@code AWAITING_ROUTE_SELECTION} by resolving the caller's
     *          {@code choiceKey} to the target branch step, then delegating
     *          to {@link #resumeWorkflowRun(String, String)}.
     * @logic Validates run state + tenant, then:
     *        <ol>
     *          <li>Loads the ROUTER step at {@code run.currentStepOrder};</li>
     *          <li>Resolves {@code choiceKey} → {@code targetStepId} via
     *              {@code routerConfig.choices}; rejects unknown keys with
     *              {@link IllegalArgumentException};</li>
     *          <li>Finds the target step in the workflow's ordered step list;</li>
     *          <li>Sets {@code run.currentStepOrder = targetStep.stepOrder - 1}
     *              so the resume filter ({@code stepOrder > currentStepOrder})
     *              includes the target onwards;</li>
     *          <li>Delegates to {@link #resumeWorkflowRun} with the row's
     *              {@code currentPayload} as the input to the target step.</li>
     *        </ol>
     *        <p>Limitation: the existing resume loop only dispatches AGENT
     *        steps. If the chosen branch is the head of a CONDITION/PARALLEL/
     *        LOOP chain it falls back to AGENT semantics, matching the
     *        documented constraint on resume.
     */
    public void continueAfterRouteSelection(String workflowRunId, String choiceKey) {
        if (workflowRunId == null || choiceKey == null || choiceKey.isBlank()) {
            throw new IllegalArgumentException("workflowRunId and choiceKey are required");
        }
        WorkflowRun run = workflowRunRepository.findById(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("WorkflowRun not found: " + workflowRunId));
        if (!callerOrgId().equals(run.getOrgId())) {
            throw new IllegalArgumentException("WorkflowRun not found: " + workflowRunId);
        }
        if (run.getStatus() != RunStatus.AWAITING_ROUTE_SELECTION) {
            log.warn("Attempted to continue WorkflowRun {} but it is not AWAITING_ROUTE_SELECTION (Status: {}).",
                    workflowRunId, run.getStatus());
            return;
        }

        // DAG-3c: a DAG run paused at a ROUTER HITL selector resumes via the frontier — inject a
        // branched output activating only the chosen port (the edge whose condition == choiceKey).
        if (isDagRun(run)) {
            webSocketHandler.broadcastEvent(run.getWorkflowId(), "RouteSelected",
                    Map.of("runId", workflowRunId, "choiceKey", choiceKey));
            resumeViaDag(run, dagSettledRouteOutput(run, choiceKey));
            return;
        }

        List<WorkflowStep> steps = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(run.getWorkflowId());
        WorkflowStep routerStep = steps.stream()
                .filter(s -> s.getStepOrder() != null && s.getStepOrder() == run.getCurrentStepOrder())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ROUTER step at order " + run.getCurrentStepOrder() + " not found for run " + workflowRunId));
        com.operativus.agentmanager.core.model.RouterStepConfig routerConfig = routerStep.getRouterConfig();
        if (routerConfig == null || routerConfig.choices() == null) {
            throw new IllegalStateException("Paused ROUTER step " + routerStep.getId() + " has no routerConfig");
        }
        String targetStepId = routerConfig.choices().get(choiceKey);
        if (targetStepId == null) {
            throw new IllegalArgumentException(
                    "choiceKey '" + choiceKey + "' is not a declared choice for step " + routerStep.getId());
        }
        WorkflowStep targetStep = steps.stream()
                .filter(s -> targetStepId.equals(s.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ROUTER target stepId '" + targetStepId + "' not found in workflow " + run.getWorkflowId()));

        // Position the cursor just before the target so the resume filter
        // (stepOrder > currentStepOrder) includes it. The status flip from
        // AWAITING_ROUTE_SELECTION → RUNNING happens inside resumeWorkflowRun.
        run.setCurrentStepOrder(targetStep.getStepOrder() - 1);
        workflowRunRepository.save(run);
        webSocketHandler.broadcastEvent(run.getWorkflowId(), "RouteSelected",
                Map.of("runId", workflowRunId, "stepId", routerStep.getId(),
                        "choiceKey", choiceKey, "branchedTo", targetStepId));
        resumeWorkflowRun(workflowRunId, run.getCurrentPayload());
    }

    /**
     * @summary Resumes a previously paused WorkflowRun asynchronously.
     * @logic Re-hydrates state from the WorkflowRun repository, discards chronological steps that have already been executed based on current_step_order, and injects the recently completed human-approved LLM response into the next pending step.
     */
    public void resumeWorkflowRun(String workflowRunId, String preCalculatedOutput) {
        WorkflowRun run = workflowRunRepository.findById(workflowRunId)
             .orElseThrow(() -> new IllegalArgumentException("WorkflowRun not found: " + workflowRunId));

        // PAUSED (HITL tool approval) and AWAITING_ROUTE_SELECTION (REQ-DR-4 HITL
        // route selection) both resume via this same loop; the latter arrives here
        // from continueAfterRouteSelection after the branch is resolved.
        // REQ-HR-3: AWAITING_HUMAN_REVIEW also resumes here — set by the new
        // unified HumanReview pause path; WorkflowStepResumeHandler.onDecided
        // invokes this after the operator hits /decide.
        if (run.getStatus() != RunStatus.PAUSED
                && run.getStatus() != RunStatus.AWAITING_ROUTE_SELECTION
                && run.getStatus() != RunStatus.AWAITING_HUMAN_REVIEW) {
            log.warn("Attempted to resume WorkflowRun {} but it is not in a resumable state (Status: {}).",
                    workflowRunId, run.getStatus());
            return;
        }

        // DAG-3c: a run paused on the DAG engine carries a dag_frontier — resume the exact graph
        // from it instead of flat-replaying step_order (which would re-run a different, wrong graph).
        if (isDagRun(run)) {
            String settledContent = (preCalculatedOutput == null || preCalculatedOutput.isEmpty())
                    ? run.getCurrentPayload() : preCalculatedOutput;
            resumeViaDag(run, dagSettledAgentOutput(run, settledContent));
            return;
        }

        run.setStatus(RunStatus.RUNNING);
        workflowRunRepository.save(run);

        // F4 — same propagation problem as executeWorkflowAsync. Capture caller bindings and
        // rebind on the worker, plus explicitly bind a fresh workflowRunId array so
        // setWorkflowRunId(run.getId()) below stops being a silent no-op.
        final com.operativus.agentmanager.core.callback.AgentContextSnapshot resumeSnapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        final String[] resumeWorkflowRunIdHolder = new String[1];

        Runnable task = ContextSnapshotFactory.builder().build().captureAll().wrap(() -> resumeSnapshot.run(() ->
                ScopedValue.where(com.operativus.agentmanager.core.callback.AgentContextHolder.workflowRunId, resumeWorkflowRunIdHolder).run(() -> {
            Span span = tracer.nextSpan().name("WorkflowService.resumeWorkflowRun").tag("workflowRunId", workflowRunId).start();
            try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                com.operativus.agentmanager.core.callback.AgentContextHolder.setWorkflowRunId(run.getId());
                log.info("Resuming background execution for Workflow Run: {}", workflowRunId);
                
                String workflowId = run.getWorkflowId();
                webSocketHandler.broadcastEvent(workflowId, "RunResumed", Map.of("runId", workflowRunId));

                List<WorkflowStep> remainingSteps = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)
                    .stream()
                    .filter(step -> step.getStepOrder() > run.getCurrentStepOrder())
                    .collect(Collectors.toList());

                // REQ-DR-6 PR-4: when the operator approves a CONDITION confirmation
                // there is no operator-provided output — fall back to the payload
                // captured at pause time. Existing HITL tool-approval flow always
                // passes a non-empty output, so its behavior is unchanged.
                String currentInput = (preCalculatedOutput == null || preCalculatedOutput.isEmpty())
                        ? run.getCurrentPayload()
                        : preCalculatedOutput;

                for (WorkflowStep step : remainingSteps) {
                    Span stepSpan = tracer.nextSpan().name("WorkflowService.step").tag("stepId", step.getId()).tag("agentId", step.getAgentId() != null ? step.getAgentId() : "none").start();
                    try (Tracer.SpanInScope stepWs = tracer.withSpan(stepSpan)) {
                        run.setCurrentStepOrder(step.getStepOrder());
                    run.setCurrentPayload(currentInput);
                    workflowRunRepository.save(run);

                    log.info("Executing Pending Workflow Step: {}, Agent: {}", step.getId(), step.getAgentId());
                    webSocketHandler.broadcastEvent(workflowId, WorkflowConstants.WS_EVENT_STEP_STARTED, Map.of("stepId", step.getId(), "agentId", step.getAgentId()));

                    String identity = SecurityPrincipals.SYSTEM_PRINCIPAL;
                    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.isAuthenticated() && !SecurityPrincipals.ANONYMOUS_USER.equals(auth.getPrincipal())) {
                        identity = auth.getName();
                    }
                    // Resolve the agent in the RUN's tenant (run.getOrgId()), not the principal —
                    // resume jobs have no auth context, so the principal is SYSTEM_PRINCIPAL and
                    // using it as the org would never find a real tenant's agents. See the
                    // executeAgentStep doc-block for the same fix on the forward path.
                    String effectiveOrgId = (run.getOrgId() != null && !run.getOrgId().isBlank())
                            ? run.getOrgId() : TenantConstants.DEFAULT_SYSTEM_ORG;
                    // Execute Agent Run synchronously
                    RunResponse response = agentOperations.run(step.getAgentId(), currentInput, null, run.getSessionId(), identity, effectiveOrgId, false, null);

                    if (response.status() == RunStatus.PAUSED) {
                        log.warn("Workflow Step {} paused for HITL Approval. Halting workflow execution.", step.getId());
                        run.setStatus(RunStatus.PAUSED);
                        run.setCurrentPayload(currentInput);
                        workflowRunRepository.save(run);
                        webSocketHandler.broadcastEvent(workflowId, "RunPaused", Map.of("stepId", step.getId(), "reason", "HITL_APPROVAL_REQUIRED"));
                        return; // Halt workflow execution again.
                    }

                    currentInput = response.content();
                    log.info("Completed Workflow Step: {}", step.getId());
                    webSocketHandler.broadcastEvent(workflowId, "StepCompleted", Map.of("stepId", step.getId(), "output", currentInput));
                    } catch (Exception e) {
                        stepSpan.error(e);
                        throw e;
                    } finally {
                        stepSpan.end();
                    }
                }

                log.info("Workflow execution completed: {}", workflowId);
                run.setStatus(RunStatus.COMPLETED);
                run.setCurrentPayload(currentInput);
                workflowRunRepository.save(run);
                webSocketHandler.broadcastEvent(workflowId, "RunCompleted", Map.of("sessionId", run.getSessionId(), "finalOutput", currentInput));

            } catch (Exception e) {
                log.error("Workflow resumption failed: {}", workflowRunId, e);
                span.error(e);
                run.setStatus(RunStatus.FAILED);
                workflowRunRepository.save(run);
                webSocketHandler.broadcastEvent(run.getWorkflowId(), "RunFailed", Map.of("error", e.getMessage()));
            } finally {
                com.operativus.agentmanager.core.callback.AgentContextHolder.clear();
                span.end();
            }
        })));

        Executors.newVirtualThreadPerTaskExecutor().submit(task);
    }

    /**
     * Cancels a workflow_run, transitioning to CANCELLED. Used by ApprovalService when a
     * HITL approval is REJECTED inside a workflow — the approval can't proceed, so the
     * workflow can't either. Idempotent: returns silently if the workflow_run is already
     * in a terminal state. The reason is logged only; the linked agent_run carries it
     * in agent_runs.output. See Tier 2.4 PR 7 fix (F-A).
     */
    /**
     * @summary Cooperative cancellation poll used by {@code executeWorkflowAsync}'s
     *          step loop and LOOP iteration to short-circuit when a concurrent caller
     *          (REST cancel endpoint, HITL reject cascade) has flipped the workflow_run
     *          to CANCELLED.
     * @logic Fresh {@code findById} each call — the in-memory {@code workflowRun} entity
     *        the lambda holds is from the entity's initial save and does NOT reflect
     *        committed writes from {@code cancelWorkflowRun}'s separate transaction.
     */
    private boolean isWorkflowRunCancelled(String workflowRunId) {
        return workflowRunRepository.findById(workflowRunId)
                .map(wr -> wr.getStatus() == RunStatus.CANCELLED)
                .orElse(false);
    }

    /** STARTED → "Started" etc., so the DAG node WS event types read "NodeStarted"/"NodeCompleted"/… */
    private static String capitalizePhase(String phase) {
        if (phase == null || phase.isEmpty()) return "";
        return phase.charAt(0) + phase.substring(1).toLowerCase();
    }

    /**
     * @summary REQ-DR-5 DAG dispatch: runs the workflow via the {@link DagWorkflowExecutor}
     *          frontier scheduler and maps its terminal {@link StepOutput} onto the
     *          {@code workflow_runs} lifecycle status + final payload.
     * @logic The executor returns the terminal output (success), or the triggering output on a
     *        node pause/failure, or a synthetic cancelled marker. Cancellation is checked first —
     *        the canceller's own committed transaction already set CANCELLED, so the DAG path must
     *        not clobber it. Runs inside {@code executeWorkflowAsync}'s bound workflow context, so
     *        the outer catch finalizes FAILED if this throws.
     */
    private void runViaDag(WorkflowRun run, List<WorkflowStep> nodes, List<WorkflowEdge> edges,
                           String initialInput, String runId, String workflowId, String sessionId) {
        com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.DagResult dr =
                dagWorkflowExecutor.run(run, nodes, edges, initialInput,
                        () -> isWorkflowRunCancelled(runId), dagEventSink());
        applyDagResult(run, dr, runId, workflowId, sessionId);
    }

    /** WS sink shared by the DAG forward + resume paths — lights up the run-graph viewer per node. */
    private com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.NodeEventSink dagEventSink() {
        return (phase, rid, wfId, nodeId, nodeName, kind) -> webSocketHandler.broadcastEvent(
                wfId, "Node" + capitalizePhase(phase),
                Map.of("runId", rid, "nodeId", nodeId,
                        "nodeName", nodeName == null ? "" : nodeName,
                        "kind", kind == null ? "" : kind.name(),
                        "phase", phase));
    }

    /**
     * Maps a {@link DagWorkflowExecutor.DagResult} onto the {@code workflow_runs} lifecycle. On a HITL
     * pause it persists the {@link DagFrontier} (DAG-3c) and maps the pause kind to the run status; on
     * completion/failure it clears any stale frontier. Cancellation is checked first — the canceller's
     * own committed transaction already set CANCELLED, so this must not clobber it.
     */
    private void applyDagResult(WorkflowRun run,
                                com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.DagResult dr,
                                String runId, String workflowId, String sessionId) {
        if (isWorkflowRunCancelled(runId)) {
            log.info("DAG workflow run {} cancelled mid-flight", runId);
            webSocketHandler.broadcastEvent(workflowId, "RunCancelled", Map.of("runId", runId));
            return; // status already CANCELLED by the canceller's committed transaction
        }
        StepOutput terminal = dr.output();
        if (terminal.paused()) {
            run.setStatus(statusForPause(terminal.pauseKind()));
            run.setCurrentPayload(terminal.contentText());
            run.setDagFrontier(dr.frontier()); // DAG-3c: persist the frontier so resume can continue
            workflowRunRepository.save(run);
            webSocketHandler.broadcastEvent(workflowId, "RunPaused",
                    Map.of("runId", runId, "pauseKind", terminal.pauseKind() == null ? "" : terminal.pauseKind()));
            return;
        }
        // No longer paused — drop any frontier from a prior pause so a completed/failed run carries none.
        run.setDagFrontier(null);
        if (!terminal.success()) {
            String error = terminal.error() != null ? terminal.error() : "DAG node failed";
            run.setStatus(RunStatus.FAILED);
            run.setCurrentPayload(error);
            workflowRunRepository.save(run);
            webSocketHandler.broadcastEvent(workflowId, "RunFailed", Map.of("error", error));
            return;
        }
        String finalOutput = terminal.contentText();
        run.setStatus(RunStatus.COMPLETED);
        run.setCurrentPayload(finalOutput);
        workflowRunRepository.save(run);
        webSocketHandler.broadcastEvent(workflowId, "RunCompleted",
                Map.of("sessionId", sessionId, "finalOutput", finalOutput));
    }

    /** Pause-kind → resumable run status (REQ-DR-4/HR-3). Route selection and human review get their
     *  own statuses so the FE state machine + resume entry points behave as on the flat engine. */
    private static RunStatus statusForPause(String pauseKind) {
        if (PauseKind.ROUTE.equals(pauseKind)) return RunStatus.AWAITING_ROUTE_SELECTION;
        if (PauseKind.REVIEW.equals(pauseKind)) return RunStatus.AWAITING_HUMAN_REVIEW;
        return RunStatus.PAUSED; // agent / tool / unknown
    }

    /**
     * True when this run paused on the DAG engine — the only path that writes a {@code dag_frontier}.
     * Deliberately NOT gated on {@link #dagEnabled}: a frontier-carrying run can only be resumed by
     * re-entering its exact graph; flat-replaying it would corrupt the run. Flipping the flag off
     * stops NEW dispatches from taking the DAG path but never reroutes an in-flight pause.
     */
    private boolean isDagRun(WorkflowRun run) {
        return run.getDagFrontier() != null;
    }

    /**
     * DAG-3c resume: rehydrate the paused run's frontier, inject the settled node's output, and
     * re-enter the exact graph via {@link DagWorkflowExecutor#resume}. Runs on a fresh VT (mirrors the
     * flat {@code resumeWorkflowRun}) so the caller (REST/HITL settle) returns immediately.
     *
     * @param settledOutput the operator-settled output for the paused node (an injected AGENT/review
     *                      result, or a ROUTER {@code branched(choiceKey)})
     */
    private void resumeViaDag(WorkflowRun run, StepOutput settledOutput) {
        DagFrontier frontier = run.getDagFrontier();
        if (frontier == null || frontier.pausedNodeIds().isEmpty()) {
            // Fail fast — never silently flat-replay a DAG run (corrupts the graph). DAG-3c §3.
            throw new BusinessValidationException(
                    "Cannot DAG-resume run " + run.getId() + ": dag_frontier is absent or has no paused node");
        }
        String workflowId = run.getWorkflowId();
        String runId = run.getId();
        List<WorkflowStep> nodes = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId);
        List<WorkflowEdge> edges = workflowEdgeRepository.findByWorkflowIdOrderByFromStepIdAsc(workflowId);

        run.setStatus(RunStatus.RUNNING);
        workflowRunRepository.save(run);
        webSocketHandler.broadcastEvent(workflowId, "RunResumed", Map.of("runId", runId));

        final com.operativus.agentmanager.core.callback.AgentContextSnapshot resumeSnapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        final String[] holder = new String[1];
        Runnable task = ContextSnapshotFactory.builder().build().captureAll().wrap(() -> resumeSnapshot.run(() ->
                ScopedValue.where(com.operativus.agentmanager.core.callback.AgentContextHolder.workflowRunId, holder).run(() -> {
            Span span = tracer.nextSpan().name("WorkflowService.resumeViaDag").tag("workflowRunId", runId).start();
            try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                com.operativus.agentmanager.core.callback.AgentContextHolder.setWorkflowRunId(runId);
                com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.DagResult dr =
                        dagWorkflowExecutor.resume(run, nodes, edges, frontier, settledOutput,
                                () -> isWorkflowRunCancelled(runId), dagEventSink());
                applyDagResult(run, dr, runId, workflowId, run.getSessionId());
            } catch (Exception e) {
                log.error("DAG workflow resume failed: {}", runId, e);
                span.error(e);
                run.setStatus(RunStatus.FAILED);
                run.setDagFrontier(null);
                workflowRunRepository.save(run);
                webSocketHandler.broadcastEvent(workflowId, "RunFailed", Map.of("error", e.getMessage()));
            } finally {
                com.operativus.agentmanager.core.callback.AgentContextHolder.clear();
                span.end();
            }
        })));
        Executors.newVirtualThreadPerTaskExecutor().submit(task);
    }

    /** Builds the injected output for the paused node being settled (AGENT/review = success carrier). */
    private StepOutput dagSettledAgentOutput(WorkflowRun run, String content) {
        DagFrontier frontier = run.getDagFrontier();
        String pausedNodeId = frontier.pausedNodeIds().get(0);
        WorkflowStep node = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(run.getWorkflowId()).stream()
                .filter(s -> pausedNodeId.equals(s.getId())).findFirst().orElse(null);
        com.operativus.agentmanager.core.model.enums.NodeKind kind = node != null
                ? com.operativus.agentmanager.core.model.enums.NodeKind.fromAction(
                        com.operativus.agentmanager.core.model.enums.StepActionType.fromString(node.getAction()))
                : com.operativus.agentmanager.core.model.enums.NodeKind.AGENT;
        String name = (node != null && node.getAgentId() != null && !node.getAgentId().isBlank())
                ? node.getAgentId() : pausedNodeId;
        return StepOutput.success(pausedNodeId, name, kind, null,
                content == null ? "" : content, List.of(), java.time.Instant.now(), java.time.Instant.now(), null, null);
    }

    /**
     * The ROUTER step a paused run is awaiting — the single resolution point shared by the
     * route-options listing and the route settle. DAG runs resolve via the frontier's paused node,
     * descending the nested-pause chain to the INNERMOST paused node (a ROUTER inside a paused
     * sub-workflow — the parent WORKFLOW node has no routerConfig of its own); flat runs fall back
     * to the {@code current_step_order} cursor (which the DAG path never sets). Null when no
     * router step can be resolved.
     */
    public WorkflowStep pausedRouterStep(WorkflowRun run) {
        DagFrontier frontier = run.getDagFrontier();
        if (isDagRun(run) && !frontier.pausedNodeIds().isEmpty()) {
            String innerWorkflowId = run.getWorkflowId();
            String innerNodeId = frontier.pausedNodeIds().get(0);
            DagFrontier innerFrontier = frontier;
            while (innerFrontier.nestedPauses().containsKey(innerNodeId)) {
                com.operativus.agentmanager.core.model.workflow.NestedPause np =
                        innerFrontier.nestedPauses().get(innerNodeId);
                innerWorkflowId = np.childWorkflowId();
                innerFrontier = np.childFrontier();
                if (innerFrontier == null || innerFrontier.pausedNodeIds().isEmpty()) {
                    return null;
                }
                innerNodeId = innerFrontier.pausedNodeIds().get(0);
            }
            final String routerNodeId = innerNodeId;
            return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(innerWorkflowId).stream()
                    .filter(s -> routerNodeId.equals(s.getId())).findFirst().orElse(null);
        }
        if (run.getCurrentStepOrder() == null) return null;
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(run.getWorkflowId()).stream()
                .filter(s -> java.util.Objects.equals(s.getStepOrder(), run.getCurrentStepOrder()))
                .filter(s -> s.getRouterConfig() != null)
                .findFirst().orElse(null);
    }

    /**
     * Builds the injected branched output for a paused ROUTER node, activating only the chosen port.
     * When the pause is NESTED (the ROUTER lives inside a paused sub-workflow), the choice is
     * validated against the INNERMOST paused ROUTER's config (via {@link #pausedRouterStep}) while
     * the built output still targets the TOP-level paused node; the WORKFLOW executor's
     * {@code resumeNested} retargets it down one level per nesting hop.
     */
    private StepOutput dagSettledRouteOutput(WorkflowRun run, String choiceKey) {
        DagFrontier frontier = run.getDagFrontier();
        if (frontier == null || frontier.pausedNodeIds().isEmpty()) {
            throw new BusinessValidationException(
                    "Cannot DAG-resume run " + run.getId() + ": dag_frontier is absent or has no paused node");
        }
        String pausedNodeId = frontier.pausedNodeIds().get(0);
        WorkflowStep node = pausedRouterStep(run);
        com.operativus.agentmanager.core.model.RouterStepConfig cfg = node != null ? node.getRouterConfig() : null;
        if (cfg == null || cfg.choices() == null || !cfg.choices().containsKey(choiceKey)) {
            throw new IllegalArgumentException(
                    "choiceKey '" + choiceKey + "' is not a declared choice for ROUTER node "
                            + (node != null ? node.getId() : pausedNodeId));
        }
        String name = (node.getAgentId() != null && !node.getAgentId().isBlank()) ? node.getAgentId() : pausedNodeId;
        // The DAG edge out of a ROUTER is labelled with the choice key (edge.condition == choiceKey),
        // so the active port IS the choice key — dead-path elimination prunes the unchosen branches.
        return StepOutput.branched(pausedNodeId, name, com.operativus.agentmanager.core.model.enums.NodeKind.ROUTER,
                "ROUTER", run.getCurrentPayload(), List.of(choiceKey), List.of(),
                java.time.Instant.now(), java.time.Instant.now());
    }

    @org.springframework.transaction.annotation.Transactional
    public void cancelWorkflowRun(String workflowRunId, String reason) {
        workflowRunRepository.findById(workflowRunId).ifPresent(wr -> {
            if (wr.getStatus() == RunStatus.COMPLETED || wr.getStatus() == RunStatus.FAILED
                    || wr.getStatus() == RunStatus.CANCELLED) {
                log.debug("cancelWorkflowRun no-op: workflow_run {} already terminal ({})",
                        workflowRunId, wr.getStatus());
                return;
            }
            wr.setStatus(RunStatus.CANCELLED);
            wr.setUpdatedAt(LocalDateTime.now());
            workflowRunRepository.save(wr);
            log.info("Workflow run {} cancelled: {}", workflowRunId, reason);
        });
    }

    // --- Mappers ---

    /**
     * @summary Translates a Workflow entity into an immutable WorkflowDTO.
     * @logic Maps scalar fields and coalesces null timestamps.
     */
    private WorkflowDTO toDto(Workflow workflow) {
        return toDto(workflow, (int) workflowStepRepository.countByWorkflowId(workflow.getId()));
    }

    private WorkflowDTO toDto(Workflow workflow, int stepCount) {
        return new WorkflowDTO(
                workflow.getId(),
                workflow.getName(),
                workflow.getDescription(),
                stepCount,
                workflow.getCreatedAt() != null ? workflow.getCreatedAt() : LocalDateTime.now(),
                workflow.getUpdatedAt() != null ? workflow.getUpdatedAt() : LocalDateTime.now()
        );
    }

    /**
     * @summary Translates a WorkflowStep entity into an immutable WorkflowStepDTO.
     * @logic Maps string identifiers, ordering scalars, and coalesces null timestamps.
     */
    /**
     * Executes a single agent step and returns the output. Returns WorkflowConstants.PAUSED_SENTINEL if HITL approval is needed.
     *
     * <p>The agent is resolved in {@code runOrgId} — the workflow run's tenant (from the
     * workflow row), NOT the security principal. Workflow execution jobs run without an auth
     * context, so the principal here is {@code SYSTEM_PRINCIPAL}; using it as the org would make
     * {@code AgentRegistry.findById} look in a "system" tenant and never find a real tenant's
     * agents. The principal is still passed as the {@code userId} for audit attribution.</p>
     */
    private String executeAgentStep(WorkflowStep step, String workflowId, String runId, String sessionId, String input, String runOrgId) {
        String identity = SecurityPrincipals.SYSTEM_PRINCIPAL;
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !SecurityPrincipals.ANONYMOUS_USER.equals(auth.getPrincipal())) {
            identity = auth.getName();
        }
        String effectiveOrgId = (runOrgId != null && !runOrgId.isBlank()) ? runOrgId : TenantConstants.DEFAULT_SYSTEM_ORG;
        RunResponse response = agentOperations.run(step.getAgentId(), input, null, sessionId, identity, effectiveOrgId, false, null);
        if (response.status() == RunStatus.PAUSED) {
            return WorkflowConstants.PAUSED_SENTINEL;
        }
        return response.content();
    }

    /**
     * Evaluates a condition expression. The agentId field stores the condition.
     *
     * <p>Supported prefixes (case-insensitive prefix match; value preserves case):
     * <ul>
     *   <li>{@code contains:<text>} — case-insensitive substring match</li>
     *   <li>{@code not_contains:<text>} — inverse of {@code contains:}</li>
     *   <li>{@code length>N} / {@code length<N} — numeric length compare</li>
     *   <li>{@code jsonpath:<expr>} — REQ-DR-6: Jayway JSONPath evaluated against
     *       the prior step output. The wrapped-text strategy from
     *       {@link com.operativus.agentmanager.compute.routing.RuleRouteSelector}
     *       applies (non-JSON inputs accessed via {@code $.text}). Truthy
     *       resolution = true; PathNotFound / parse error = false.</li>
     *   <li>{@code llm:<yes/no question>} — REQ-DR-6: yes/no judgment via the
     *       primary LLM ({@link com.operativus.agentmanager.compute.routing.LlmConditionEvaluator}).
     *       Failures evaluate to false defensively.</li>
     *   <li>{@code not_empty} / {@code empty} — input blank check</li>
     * </ul>
     *
     * <p>Returns true on unknown expressions (preserves prior behavior:
     * skip-on-false means unknown-expression workflows continue rather than
     * silently skipping every other step).
     */
    private boolean evaluateCondition(String conditionExpr, String input) {
        return conditionEvaluator.evaluate(conditionExpr, input);
    }

    /**
     * REQ-DR-6 PR-4: computes the {@code workflow_runs.current_step_order} value
     * to persist before pausing on a {@code requires_confirmation=true} CONDITION
     * step. The resumeWorkflowRun loop runs steps with {@code stepOrder > currentStepOrder},
     * so this method positions the cursor one slot before the step that should
     * execute on resume.
     *
     * <ul>
     *   <li>Condition TRUE → cursor at the CONDITION step's order (resume picks
     *       up at the next step in order).</li>
     *   <li>Condition FALSE + SKIP → cursor at the next step's order (resume
     *       skips it, picks up at +2).</li>
     *   <li>Condition FALSE + ELSE_BRANCH → cursor at elseStep.stepOrder - 1
     *       (resume picks up at elseStep). Returns -1 if elseStepId is missing
     *       from the step list — caller fails the run.</li>
     *   <li>CANCEL is rejected at create time (see validateRequiresConfirmation);
     *       this method never sees it.</li>
     * </ul>
     */
    private int computeConditionPlannedCursor(WorkflowStep step, List<WorkflowStep> steps,
                                              boolean conditionMet, int currentIdx) {
        if (conditionMet) {
            return step.getStepOrder();
        }
        var policy = com.operativus.agentmanager.core.model.enums.OnRejectPolicy.fromString(step.getOnReject());
        if (policy == com.operativus.agentmanager.core.model.enums.OnRejectPolicy.ELSE_BRANCH) {
            String elseStepId = step.getElseStepId();
            for (WorkflowStep s : steps) {
                if (s.getId().equals(elseStepId)) {
                    return s.getStepOrder() - 1;
                }
            }
            return -1;
        }
        // SKIP (default): cursor past the next step so resume's filter excludes it.
        if (currentIdx + 1 < steps.size()) {
            return steps.get(currentIdx + 1).getStepOrder();
        }
        return step.getStepOrder();
    }

    private WorkflowStepDTO toStepDto(WorkflowStep step) {
        return new WorkflowStepDTO(
                step.getId(),
                step.getWorkflowId(),
                step.getStepOrder(),
                step.getAgentId(),
                step.getAction(),
                step.getRouterConfig(),
                step.getOnReject(),
                step.getElseStepId(),
                step.isRequiresConfirmation(),
                step.getHumanReview(),
                step.getCreatedAt() != null ? step.getCreatedAt() : LocalDateTime.now(),
                step.getUpdatedAt() != null ? step.getUpdatedAt() : LocalDateTime.now()
        );
    }

    /**
     * @summary Ensures an agent_sessions row exists for the supplied sessionId so the
     *          downstream workflow_runs INSERT does not trip fk_workflow_runs_session.
     * @logic No-op when a row with this sessionId already exists (chained from an earlier
     *        conversation). When absent, persists a minimal row attributed to the synthetic
     *        {@link #WORKFLOW_RUN_SYNTHETIC_USER_ID} under the workflow's owning org, so
     *        tenant isolation holds. Mirrors {@code ScheduleExecutionPoller.autoCreateSchedulerSession}.
     */
    private void ensureSessionExists(String sessionId, String orgId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (sessionRepository.existsById(sessionId)) return;
        String resolvedOrg = (orgId != null && !orgId.isBlank())
                ? orgId
                : TenantConstants.DEFAULT_SYSTEM_ORG;
        AgentSession session = new AgentSession();
        session.setSessionId(sessionId);
        session.setUserId(WORKFLOW_RUN_SYNTHETIC_USER_ID);
        session.setOrgId(resolvedOrg);
        LocalDateTime now = LocalDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionRepository.save(session);
    }
}
