package ai.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.config.PaginationDefaultsConfig;
import ai.operativus.agentmanager.control.repository.WorkflowRepository;
import ai.operativus.agentmanager.control.repository.WorkflowRunRepository;
import ai.operativus.agentmanager.control.repository.WorkflowStepRepository;
import ai.operativus.agentmanager.control.repository.WorkflowEdgeRepository;
import ai.operativus.agentmanager.compute.workflow.WorkflowDagValidator;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.control.service.queue.WorkflowExecutionJobHandler;
import ai.operativus.agentmanager.control.service.queue.WorkflowContinueJobHandler;
import ai.operativus.agentmanager.control.service.queue.WorkflowResumeJobHandler;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.model.ContinueWorkflowRequest;
import ai.operativus.agentmanager.core.model.RouterStepConfig;
import ai.operativus.agentmanager.core.model.WorkflowRouteOptionsResponse;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.model.ExecuteWorkflowRequest;
import ai.operativus.agentmanager.core.model.ResumeWorkflowRequest;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.core.model.WorkflowContinueResponse;
import ai.operativus.agentmanager.core.model.WorkflowDTO;
import ai.operativus.agentmanager.core.model.WorkflowEdgeDTO;
import ai.operativus.agentmanager.core.model.WorkflowGraphResponse;
import ai.operativus.agentmanager.core.model.WorkflowValidationResult;
import ai.operativus.agentmanager.core.model.WorkflowLayoutDTO;
import ai.operativus.agentmanager.core.model.NodePosition;
import ai.operativus.agentmanager.core.entity.WorkflowNodeLayout;
import ai.operativus.agentmanager.core.model.WorkflowNodeRunDTO;
import ai.operativus.agentmanager.core.model.CreateWorkflowEdgeRequest;
import ai.operativus.agentmanager.core.model.UpdateWorkflowEdgeRequest;
import ai.operativus.agentmanager.core.model.WorkflowExecutionResponse;
import ai.operativus.agentmanager.core.model.WorkflowResumeResponse;
import ai.operativus.agentmanager.core.model.WorkflowRunResponse;
import ai.operativus.agentmanager.core.model.WorkflowStepDTO;
import ai.operativus.agentmanager.control.service.WorkflowService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain Responsibility: Exposes REST APIs for designing and managing multi-step Agent Workflows and their linear Steps.
 * State: Stateless
 * Dependencies: WorkflowService
 */
@RestController
@RequestMapping("/api/v1/workflows")
@PreAuthorize("isAuthenticated()")
public class WorkflowsController {

    private final WorkflowService workflowService;
    private final PersistentJobQueueService jobQueueService;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowEdgeRepository workflowEdgeRepository;
    private final ai.operativus.agentmanager.control.repository.WorkflowNodeRunRepository workflowNodeRunRepository;
    private final ai.operativus.agentmanager.control.repository.WorkflowNodeLayoutRepository workflowNodeLayoutRepository;
    private final WorkflowDagValidator dagValidator;
    private final ObjectMapper objectMapper;

    public WorkflowsController(WorkflowService workflowService,
                               PersistentJobQueueService jobQueueService,
                               WorkflowRunRepository workflowRunRepository,
                               WorkflowRepository workflowRepository,
                               WorkflowStepRepository workflowStepRepository,
                               WorkflowEdgeRepository workflowEdgeRepository,
                               ai.operativus.agentmanager.control.repository.WorkflowNodeRunRepository workflowNodeRunRepository,
                               ai.operativus.agentmanager.control.repository.WorkflowNodeLayoutRepository workflowNodeLayoutRepository,
                               WorkflowDagValidator dagValidator,
                               ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.jobQueueService = jobQueueService;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.workflowEdgeRepository = workflowEdgeRepository;
        this.workflowNodeRunRepository = workflowNodeRunRepository;
        this.workflowNodeLayoutRepository = workflowNodeLayoutRepository;
        this.dagValidator = dagValidator;
        this.objectMapper = objectMapper;
    }

    /**
     * @summary Retrieves a paginated list of designed workflow templates.
     * @logic
     * - Accepts Spring Data Pageable for page/size/sort query parameters.
     * - Forwards to WorkflowService for paginated retrieval.
     */
    @GetMapping
    public ResponseEntity<org.springframework.data.domain.Page<WorkflowDTO>> listWorkflows(@org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        return ResponseEntity.ok(workflowService.getAllWorkflows(pageable));
    }

    /** Fetches a workflow definition by id, tenant-scoped via WorkflowService. 404 when missing or cross-tenant. */
    @GetMapping("/{id}")
    public ResponseEntity<WorkflowDTO> getWorkflow(@PathVariable("id") String id) {
        return workflowService.getWorkflowById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Creates a workflow definition. ADMIN-only. Returns 201 with the persisted DTO (org_id stamped from caller). */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowDTO> createWorkflow(@RequestBody WorkflowDTO workflowDTO) {
        WorkflowDTO created = workflowService.createWorkflow(workflowDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Patches an existing workflow's mutable fields. ADMIN-only. 404 when missing or cross-tenant (service IAE → controller 404). */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowDTO> updateWorkflow(@PathVariable("id") String id, @RequestBody WorkflowDTO workflowDTO) {
        try {
            WorkflowDTO updated = workflowService.updateWorkflow(id, workflowDTO);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Deletes a workflow + cascades to its steps. ADMIN-only. Returns 204 unconditionally — silent no-op on missing/cross-tenant (pinned by WorkflowLifecycleEdgeRuntimeTest A3/A4). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable("id") String id) {
        workflowService.deleteWorkflow(id);
        return ResponseEntity.noContent().build();
    }

    /** Lists the steps that compose a workflow, ordered by step_order. Public to the caller's tenant. */
    @GetMapping("/{id}/steps")
    public ResponseEntity<List<WorkflowStepDTO>> getWorkflowSteps(@PathVariable("id") String id) {
        return ResponseEntity.ok(workflowService.getWorkflowSteps(id));
    }

    /** Appends a step to a workflow. ADMIN-only. Validates routerConfig, onReject, elseStepId, requiresConfirmation per step action type. Skips agent-existence validation for CONDITION/LOOP (those repurpose agent_id for the predicate). 404 when workflow missing or agent missing on AGENT-action step. */
    @PostMapping("/{id}/steps")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowStepDTO> addWorkflowStep(@PathVariable("id") String id, @RequestBody WorkflowStepDTO stepDTO) {
        try {
            WorkflowStepDTO added = workflowService.addWorkflowStep(id, stepDTO);
            return ResponseEntity.status(HttpStatus.CREATED).body(added);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Updates a step's editable config (agent/expression, order, router/condition config). ADMIN-only.
     *  The step's action (node kind) is immutable. 404 when workflow/step missing, cross-tenant, or
     *  cross-workflow; same per-action validations as add. */
    @PatchMapping("/{id}/steps/{stepId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowStepDTO> updateWorkflowStep(@PathVariable("id") String id,
                                                              @PathVariable("stepId") String stepId,
                                                              @RequestBody WorkflowStepDTO stepDTO) {
        try {
            return ResponseEntity.ok(workflowService.updateWorkflowStep(id, stepId, stepDTO));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Removes a step from a workflow. ADMIN-only. Returns 204. */
    @DeleteMapping("/{id}/steps/{stepId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteWorkflowStep(@PathVariable("id") String id, @PathVariable("stepId") String stepId) {
        workflowService.deleteWorkflowStep(stepId);
        return ResponseEntity.noContent().build();
    }

    // ---- DAG edges (REQ-DR-5) ------------------------------------------------------------

    /** Returns the workflow's full DAG view — step nodes + explicit edges. Tenant-scoped (404 cross-org). */
    @GetMapping("/{id}/graph")
    public ResponseEntity<WorkflowGraphResponse> getWorkflowGraph(@PathVariable("id") String id) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        List<WorkflowStepDTO> steps = workflowService.getWorkflowSteps(id);
        List<WorkflowEdgeDTO> edges = workflowEdgeRepository
                .findByWorkflowIdOrderByFromStepIdAsc(id).stream()
                .map(WorkflowsController::toEdgeDto)
                .toList();
        return ResponseEntity.ok(new WorkflowGraphResponse(steps, edges));
    }

    /**
     * Validation overlay report for the editor — cycle (if any) + orphan step ids. Read-only,
     * non-throwing (unlike the inline validate on edge-add). Tenant-scoped (404 cross-org);
     * inherits the class-level {@code isAuthenticated()} gate. An edge-less workflow reports valid.
     */
    @GetMapping("/{id}/validate")
    public ResponseEntity<WorkflowValidationResult> validateWorkflowGraph(@PathVariable("id") String id) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dagValidator.validateReport(id));
    }

    /**
     * Saved DAG-editor node layout (manual canvas positions). Read-only, inherits the class-level
     * {@code isAuthenticated()} gate; tenant-scoped (404 cross-org). Empty positions = no saved
     * layout (editor falls back to ELK auto-layout).
     */
    @GetMapping("/{id}/layout")
    public ResponseEntity<WorkflowLayoutDTO> getWorkflowLayout(@PathVariable("id") String id) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        List<NodePosition> positions = workflowNodeLayoutRepository.findByWorkflowId(id).stream()
                .map(l -> new NodePosition(l.getNodeId(), l.getPosX(), l.getPosY()))
                .toList();
        return ResponseEntity.ok(new WorkflowLayoutDTO(positions));
    }

    /**
     * Replaces a workflow's saved node layout (full set). ADMIN-only. 404 when the workflow is
     * missing/cross-tenant. Positions referencing steps that aren't part of the workflow are
     * dropped (defensive — a stale client may post an already-deleted node). Returns the persisted
     * (filtered) layout.
     */
    @PutMapping("/{id}/layout")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowLayoutDTO> saveWorkflowLayout(@PathVariable("id") String id,
                                                                @RequestBody @Valid WorkflowLayoutDTO body) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        java.util.Set<String> stepIds = workflowStepRepository
                .findByWorkflowIdOrderByStepOrderAsc(id).stream()
                .map(WorkflowStep::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<NodePosition> kept = (body == null || body.positions() == null ? List.<NodePosition>of() : body.positions())
                .stream().filter(p -> stepIds.contains(p.stepId())).toList();

        workflowNodeLayoutRepository.deleteByWorkflowId(id);
        for (NodePosition p : kept) {
            workflowNodeLayoutRepository.save(new WorkflowNodeLayout(
                    UUID.randomUUID().toString(), id, p.stepId(), p.x(), p.y()));
        }
        return ResponseEntity.ok(new WorkflowLayoutDTO(kept));
    }

    /** Lists the explicit DAG edges of a workflow. Tenant-scoped (404 cross-org). */
    @GetMapping("/{id}/edges")
    public ResponseEntity<List<WorkflowEdgeDTO>> getWorkflowEdges(@PathVariable("id") String id) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workflowEdgeRepository
                .findByWorkflowIdOrderByFromStepIdAsc(id).stream()
                .map(WorkflowsController::toEdgeDto)
                .toList());
    }

    /**
     * Adds a DAG edge between two steps of a workflow. ADMIN-only.
     * 404 when the workflow is missing/cross-tenant; 400 when an endpoint step doesn't belong to
     * the workflow, when from == to (self-loop), when the edge already exists, or when the edge
     * would introduce a cycle (the just-saved edge is rolled back before the 400).
     */
    @PostMapping("/{id}/edges")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowEdgeDTO> addWorkflowEdge(@PathVariable("id") String id,
                                                           @RequestBody @Valid CreateWorkflowEdgeRequest body) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        if (Objects.equals(body.fromStepId(), body.toStepId())) {
            throw new BusinessValidationException("An edge cannot connect a step to itself.");
        }
        java.util.Set<String> stepIds = workflowStepRepository
                .findByWorkflowIdOrderByStepOrderAsc(id).stream()
                .map(WorkflowStep::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!stepIds.contains(body.fromStepId()) || !stepIds.contains(body.toStepId())) {
            throw new BusinessValidationException("Both fromStepId and toStepId must be steps of this workflow.");
        }
        boolean duplicate = workflowEdgeRepository.findByWorkflowIdOrderByFromStepIdAsc(id).stream()
                .anyMatch(e -> e.getFromStepId().equals(body.fromStepId())
                        && e.getToStepId().equals(body.toStepId())
                        && Objects.equals(e.getCondition(), body.condition()));
        if (duplicate) {
            throw new BusinessValidationException("An identical edge already exists.");
        }
        WorkflowEdge saved = workflowEdgeRepository.save(new WorkflowEdge(
                UUID.randomUUID().toString(), id, body.fromStepId(), body.toStepId(), body.condition()));
        try {
            dagValidator.validate(id); // rejects cycles reachable from the start step
        } catch (BusinessValidationException cycle) {
            workflowEdgeRepository.delete(saved); // don't persist a graph the validator rejects
            throw cycle;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toEdgeDto(saved));
    }

    /** Removes a DAG edge. ADMIN-only. 404 when workflow missing/cross-tenant; 204 (silent no-op) when the edge id isn't found or belongs to another workflow. */
    @DeleteMapping("/{id}/edges/{edgeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteWorkflowEdge(@PathVariable("id") String id, @PathVariable("edgeId") String edgeId) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        workflowEdgeRepository.findById(edgeId)
                .filter(e -> id.equals(e.getWorkflowId()))
                .ifPresent(workflowEdgeRepository::delete);
        return ResponseEntity.noContent().build();
    }

    /**
     * Relabels a DAG edge's port (the {@code condition}). ADMIN-only. The edge's endpoints are
     * immutable — only the port label changes. 404 when the workflow is missing/cross-tenant or
     * the edge id isn't found / belongs to another workflow; 400 when the new label collides with
     * an existing edge on the same pair, or would introduce a cycle (the label is reverted before
     * the 400 — relabeling to/from the sanctioned {@code "back"} LOOP port shifts cycle adjacency).
     */
    @PatchMapping("/{id}/edges/{edgeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowEdgeDTO> updateWorkflowEdge(@PathVariable("id") String id,
                                                              @PathVariable("edgeId") String edgeId,
                                                              @RequestBody UpdateWorkflowEdgeRequest body) {
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEdge edge = workflowEdgeRepository.findById(edgeId)
                .filter(e -> id.equals(e.getWorkflowId()))
                .orElse(null);
        if (edge == null) {
            return ResponseEntity.notFound().build();
        }
        String newCondition = body == null ? null : body.condition();
        if (Objects.equals(edge.getCondition(), newCondition)) {
            return ResponseEntity.ok(toEdgeDto(edge)); // no-op relabel
        }
        boolean duplicate = workflowEdgeRepository.findByWorkflowIdOrderByFromStepIdAsc(id).stream()
                .anyMatch(e -> !e.getId().equals(edgeId)
                        && e.getFromStepId().equals(edge.getFromStepId())
                        && e.getToStepId().equals(edge.getToStepId())
                        && Objects.equals(e.getCondition(), newCondition));
        if (duplicate) {
            throw new BusinessValidationException("An identical edge already exists.");
        }
        String previous = edge.getCondition();
        edge.setCondition(newCondition);
        WorkflowEdge saved = workflowEdgeRepository.save(edge);
        try {
            dagValidator.validate(id); // a relabel can move an edge in/out of the 'back'-port exemption
        } catch (BusinessValidationException cycle) {
            saved.setCondition(previous);
            workflowEdgeRepository.save(saved); // don't persist a graph the validator rejects
            throw cycle;
        }
        return ResponseEntity.ok(toEdgeDto(saved));
    }

    private static WorkflowEdgeDTO toEdgeDto(WorkflowEdge e) {
        return new WorkflowEdgeDTO(e.getId(), e.getFromStepId(), e.getToStepId(), e.getCondition());
    }

    /**
     * @summary Deep-clones a workflow and all its steps, producing an independent copy with " (Copy)" suffix.
     */
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkflowDTO> cloneWorkflow(@PathVariable("id") String id) {
        try {
            WorkflowDTO cloned = workflowService.cloneWorkflow(id);
            return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * @summary Triggers asynchronous execution of a workflow. Returns the run ID immediately.
     * @param body Must contain "input" (the initial input text) and optionally "sessionId".
     */
    @PostMapping("/{id}/run")
    public ResponseEntity<WorkflowExecutionResponse> executeWorkflow(@PathVariable("id") String id, @RequestBody ExecuteWorkflowRequest body) throws Exception {
        // Tenant guard: cross-tenant run-trigger returns 404 (no job enqueued, no
        // existence-leak). The execution handler runs trusted afterwards.
        if (!workflowRepository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        // Reject 0-step workflows up front. The background WORKFLOW_EXECUTION job would
        // otherwise insert a workflow_runs row with a session_id that no step ever
        // creates, tripping fk_workflow_runs_session and retrying through 3 attempts
        // before the job permanently fails.
        if (workflowStepRepository.countByWorkflowId(id) == 0) {
            throw new BusinessValidationException(
                    "Workflow has no steps to execute. Add at least one step before running.");
        }
        String input = body == null || body.input() == null ? "" : body.input();
        String sessionId = body == null || body.sessionId() == null ? UUID.randomUUID().toString() : body.sessionId();
        String payload = objectMapper.writeValueAsString(new WorkflowExecutionJobHandler.Payload(id, input, sessionId));
        var job = jobQueueService.enqueue(WorkflowExecutionJobHandler.JOB_TYPE, null, payload, null, null);
        return ResponseEntity.accepted().body(new WorkflowExecutionResponse(job.getId(), id, sessionId));
    }

    /**
     * @summary Returns a paginated list of historical workflow runs for {@code workflowId},
     *     newest first. Observability plan Phase 1 T003.
     * @logic
     * - Uses {@link WorkflowRunRepository#findByWorkflowIdOrderByCreatedAtDesc} backed by
     *   {@code idx_workflow_runs_workflow_id_created_at} (Liquibase changeset 038a).
     * - Maps each entity to {@link WorkflowRunResponse}; pre-computes {@code durationMs} as
     *   {@code millisBetween(createdAt, updatedAt)} — both columns are server-managed so
     *   they are non-null for any persisted row, but the code still null-guards for
     *   forward compatibility with partially-initialized entities.
     * - Returns {@code 200 OK} with an empty page when no runs exist (no 404 — that would
     *   conflate "unknown workflow" with "workflow has no runs yet").
     */
    @GetMapping("/{workflowId}/runs")
    public ResponseEntity<Page<WorkflowRunResponse>> getWorkflowRuns(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PaginationDefaultsConfig.clampedPageRequest(page, size);
        // Org-scoped query: runs for a workflow the caller doesn't own return an empty page.
        // Matches the "200 + empty page when no runs" contract — no 404, no existence-leak.
        Page<WorkflowRunResponse> response = workflowRunRepository
                .findByWorkflowIdAndOrgIdOrderByCreatedAtDesc(workflowId, callerOrgId(), pageable)
                .map(WorkflowsController::toRunDto);
        return ResponseEntity.ok(response);
    }

    private static WorkflowRunResponse toRunDto(WorkflowRun r) {
        Long durationMs = (r.getCreatedAt() != null && r.getUpdatedAt() != null)
                ? ChronoUnit.MILLIS.between(r.getCreatedAt(), r.getUpdatedAt())
                : null;
        return new WorkflowRunResponse(
                r.getId(),
                r.getWorkflowId(),
                r.getSessionId(),
                r.getStatus(),
                r.getCurrentStepOrder(),
                durationMs,
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    /**
     * @summary Resumes a previously paused workflow run with optional human-provided output.
     * @param body Must contain "output" (the human-approved content to inject into the next step).
     */
    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<WorkflowResumeResponse> resumeWorkflowRun(@PathVariable("runId") String runId, @RequestBody ResumeWorkflowRequest body) throws Exception {
        // Tenant guard: use the run's denormalized orgId for a single-table check.
        // Unknown runIds pass through — WorkflowResumeJobHandler handles run-existence validation.
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run != null && !callerOrgId().equals(run.getOrgId())) {
            return ResponseEntity.notFound().build();
        }
        String output = body == null || body.output() == null ? "" : body.output();
        String payload = objectMapper.writeValueAsString(new WorkflowResumeJobHandler.Payload(runId, output));
        var job = jobQueueService.enqueue(WorkflowResumeJobHandler.JOB_TYPE, null, payload, null, null);
        return ResponseEntity.accepted().body(new WorkflowResumeResponse(job.getId(), runId));
    }

    /**
     * @summary REQ-DR-4 HITL route selection — resumes a workflow run paused at a
     *          ROUTER step ({@code RunStatus.AWAITING_ROUTE_SELECTION}) by selecting
     *          one of the declared {@code choiceKey}s from {@code routerConfig.choices}.
     *          Branch resolution + resume runs asynchronously on the job queue;
     *          returns {@code 202 Accepted}.
     *
     * @logic Cross-tenant runId → 404 (existence-leak protection, mirrors
     *        {@link #resumeWorkflowRun}). Unknown runId passes through to the
     *        handler (so the failure is recorded in the job rather than swallowed
     *        in a 404). Invalid choiceKey surfaces as a handler failure visible on
     *        the BackgroundJob row.
     */
    /**
     * @summary REQ-DR-4 — lists the router choice keys for a run paused at a ROUTER HITL gate,
     *          so the UI can render a picker that resumes via {@code POST /runs/{runId}/continue}.
     * @logic Org-scoped; unknown or cross-tenant runId → 404 (existence-leak protection, mirrors
     *        {@link #resumeWorkflowRun}). When the run is not in
     *        {@code AWAITING_ROUTE_SELECTION}, {@code awaitingRouteSelection=false} and
     *        {@code choiceKeys} is empty. Otherwise the router is resolved via
     *        {@code WorkflowService.pausedRouterStep} — frontier-aware for DAG runs (including a
     *        ROUTER nested inside a paused sub-workflow), {@code current_step_order} for flat runs —
     *        so the picker is fed by the SAME step the {@code /continue} settle will validate against.
     */
    @GetMapping("/runs/{runId}/route-options")
    public ResponseEntity<WorkflowRouteOptionsResponse> getRouteOptions(@PathVariable("runId") String runId) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null || !callerOrgId().equals(run.getOrgId())) {
            return ResponseEntity.notFound().build();
        }
        boolean awaiting = run.getStatus() == RunStatus.AWAITING_ROUTE_SELECTION;
        List<String> choiceKeys = List.of();
        String defaultChoice = null;
        if (awaiting) {
            WorkflowStep router = workflowService.pausedRouterStep(run);
            RouterStepConfig cfg = router != null ? router.getRouterConfig() : null;
            if (cfg != null && cfg.choices() != null) {
                choiceKeys = cfg.choices().keySet().stream().sorted().toList();
                defaultChoice = cfg.defaultChoice();
            }
        }
        return ResponseEntity.ok(
                new WorkflowRouteOptionsResponse(runId, run.getStatus(), awaiting, choiceKeys, defaultChoice));
    }

    /**
     * @summary REQ-DR-5 — lists the per-node execution trace of a DAG workflow run, oldest first,
     *          so the UI can render what the frontier scheduler actually ran (node, outcome, token
     *          cost). Empty for runs dispatched by the flat {@code step_order} engine.
     * @logic Org-scoped; unknown or cross-tenant runId → 404 (existence-leak protection, mirrors
     *        {@link #getRouteOptions}). Rows come from {@code workflow_node_runs}, written only by
     *        the DAG executor.
     */
    @GetMapping("/runs/{runId}/node-runs")
    public ResponseEntity<List<WorkflowNodeRunDTO>> getRunNodeRuns(@PathVariable("runId") String runId) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null || !callerOrgId().equals(run.getOrgId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workflowNodeRunRepository.findByRunIdOrderByStartedAtAsc(runId).stream()
                .map(WorkflowNodeRunDTO::from)
                .toList());
    }

    /**
     * @summary REQ-DR-5 (DAG-6) — the nested sub-workflow traces of a run: node executions that
     *          happened INSIDE the run's WORKFLOW nodes. Children execute under derived run ids
     *          ({@code <parentRunId>#<nodeId>#<uuid>}, recursively), so they never appear in the
     *          plain {@code /node-runs} trace; this lists them grouped per child invocation so the
     *          run viewer can expand a WORKFLOW node. Deeper descendants are attributed to the
     *          top-level WORKFLOW node they hang under, each invocation as its own group.
     * @logic Org-scoped via the PARENT run (unknown/cross-tenant runId → 404, mirrors
     *        {@link #getRunNodeRuns}); child rows are reachable only through their parent's prefix.
     */
    @GetMapping("/runs/{runId}/child-node-runs")
    public ResponseEntity<List<ai.operativus.agentmanager.core.model.WorkflowChildNodeRunsDTO>> getRunChildNodeRuns(
            @PathVariable("runId") String runId) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null || !callerOrgId().equals(run.getOrgId())) {
            return ResponseEntity.notFound().build();
        }
        String prefix = runId + "#";
        Map<String, List<ai.operativus.agentmanager.core.entity.WorkflowNodeRun>> byChildRun =
                workflowNodeRunRepository.findByRunIdStartingWithOrderByStartedAtAsc(prefix).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                ai.operativus.agentmanager.core.entity.WorkflowNodeRun::getRunId,
                                java.util.LinkedHashMap::new,
                                java.util.stream.Collectors.toList()));
        List<ai.operativus.agentmanager.core.model.WorkflowChildNodeRunsDTO> groups =
                byChildRun.entrySet().stream().map(e -> {
                    // Derived id: <parentRunId>#<nodeId>#<uuid>[#...] — the first segment after the
                    // parent prefix is the top-level WORKFLOW node this invocation hangs under.
                    String parentNodeId = e.getKey().substring(prefix.length()).split("#", 2)[0];
                    String childWorkflowId = e.getValue().get(0).getWorkflowId();
                    return new ai.operativus.agentmanager.core.model.WorkflowChildNodeRunsDTO(
                            parentNodeId, e.getKey(), childWorkflowId,
                            e.getValue().stream().map(WorkflowNodeRunDTO::from).toList());
                }).toList();
        return ResponseEntity.ok(groups);
    }

    @PostMapping("/runs/{runId}/continue")
    public ResponseEntity<WorkflowContinueResponse> continueWorkflowRun(
            @PathVariable("runId") String runId,
            @RequestBody @Valid ContinueWorkflowRequest body) throws Exception {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run != null && !callerOrgId().equals(run.getOrgId())) {
            return ResponseEntity.notFound().build();
        }
        String payload = objectMapper.writeValueAsString(
                new WorkflowContinueJobHandler.Payload(runId, body.choiceKey()));
        var job = jobQueueService.enqueue(WorkflowContinueJobHandler.JOB_TYPE, null, payload, null, null);
        return ResponseEntity.accepted().body(new WorkflowContinueResponse(job.getId(), runId));
    }

    /**
     * @summary Cancels a workflow run. Idempotent on already-terminal rows.
     * @logic
     *   - Unknown runId returns 404 (NOT pass-through like resume — cancel is destructive,
     *     fail loudly so clients don't silently retry).
     *   - Cross-tenant runId returns 404 (existence-leak protection).
     *   - Terminal rows (COMPLETED / FAILED / CANCELLED) are idempotent no-ops — return 204.
     *   - PAUSED / RUNNING rows transition to CANCELLED via
     *     {@link WorkflowService#cancelWorkflowRun(String, String)}.
     *   - In-flight virtual-thread executors are NOT interrupted by this call — the row
     *     flips to CANCELLED and any subsequent terminal write is guarded by the
     *     finalizer's status check. Mid-flight VT interruption is a separate, deliberately-
     *     deferred feature mirroring the agent-side pattern documented in
     *     {@link ai.operativus.agentmanager.integration.runs.BackgroundRunsRuntimeTest}.
     */
    @DeleteMapping("/runs/{runId}")
    public ResponseEntity<Void> cancelWorkflowRun(@PathVariable("runId") String runId) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        if (!callerOrgId().equals(run.getOrgId())) {
            return ResponseEntity.notFound().build();
        }
        workflowService.cancelWorkflowRun(runId, "user-cancelled via DELETE /runs/" + runId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves the caller's {@code orgId} from {@link AgentContextHolder}, falling back to
     * {@link TenantConstants#DEFAULT_SYSTEM_ORG} when no auth context is present. Mirrors the
     * pattern used by {@code KnowledgeBaseController.callerOrgId} and {@code ScheduleService}.
     */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }
}
