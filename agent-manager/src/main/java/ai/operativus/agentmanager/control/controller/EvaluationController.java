package ai.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.control.service.queue.EvaluationRunJobHandler;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.EvaluationCase;
import ai.operativus.agentmanager.core.entity.EvaluationRun;
import ai.operativus.agentmanager.core.entity.EvaluationSuite;
import ai.operativus.agentmanager.core.model.AddCaseRequest;
import ai.operativus.agentmanager.core.model.CreateSuiteRequest;
import ai.operativus.agentmanager.core.model.EvaluationCaseDTO;
import ai.operativus.agentmanager.core.model.EvaluationResultDTO;
import ai.operativus.agentmanager.core.model.EvaluationRunDTO;
import ai.operativus.agentmanager.core.model.EvaluationSuiteDTO;
import ai.operativus.agentmanager.core.model.SubmitFeedbackRequest;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.core.registry.EvaluationOperations;
import ai.operativus.agentmanager.control.repository.EvaluationSuiteRepository;
import ai.operativus.agentmanager.control.repository.EvaluationCaseRepository;
import ai.operativus.agentmanager.control.repository.EvaluationRunRepository;
import ai.operativus.agentmanager.control.repository.EvaluationResultRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Exposes REST APIs for managing Evaluation Suites, Cases, Runs, and Results.
 * State: Stateless
 * Dependencies: EvaluationService, EvaluationSuiteRepository, EvaluationCaseRepository, EvaluationRunRepository, EvaluationResultRepository
 */
@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    record AggregateEvalMetrics(
        long totalRuns, long totalCases, long passedCases, long failedCases,
        double passRate, double averageScore, double averageLatencyMs
    ) {}

    private final EvaluationOperations evaluationService;
    private final EvaluationSuiteRepository suiteRepository;
    private final EvaluationCaseRepository caseRepository;
    private final EvaluationRunRepository runRepository;
    private final EvaluationResultRepository resultRepository;
    private final PersistentJobQueueService jobQueueService;
    private final ObjectMapper objectMapper;

    public EvaluationController(EvaluationOperations evaluationService,
                                EvaluationSuiteRepository suiteRepository,
                                EvaluationCaseRepository caseRepository,
                                EvaluationRunRepository runRepository,
                                EvaluationResultRepository resultRepository,
                                PersistentJobQueueService jobQueueService,
                                ObjectMapper objectMapper) {
        this.evaluationService = evaluationService;
        this.suiteRepository = suiteRepository;
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.jobQueueService = jobQueueService;
        this.objectMapper = objectMapper;
    }

    // --- Suites ---

    /**
     * @summary Retrieves a paginated list of Evaluation Suites owned by the caller's org.
     * @logic G2 — was {@code findAll()} (cross-tenant). Now filters by
     *        {@code AgentContextHolder.getOrgId()}; pre-G2 a tenant could enumerate
     *        every other tenant's suites by listing.
     * @logic G3 — was unbounded {@code List<EvaluationSuite>}. Now returns
     *        {@code Page<EvaluationSuite>} with the standard nested-content wire shape
     *        pinned by {@code spring.data.web.pageable.serialization-mode=direct}.
     *        Default size 20; callers can pass {@code ?page=N&size=M}.
     */
    @GetMapping("/suites")
    public org.springframework.data.domain.Page<EvaluationSuiteDTO> listSuites(
            @org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        return suiteRepository.findAllByOrgId(callerOrgId(), pageable).map(EvaluationSuiteDTO::from);
    }

    /**
     * @summary Retrieves a specific Evaluation Suite by ID (caller-org scoped).
     * @logic G2 — was {@code findById(id)} (cross-tenant; existence-leak vector).
     *        Now uses {@code findByIdAndOrgId}; a cross-tenant id returns 404 with
     *        the same shape as a missing id.
     */
    @GetMapping("/suites/{id}")
    public ResponseEntity<EvaluationSuiteDTO> getSuite(@PathVariable String id) {
        return suiteRepository.findByIdAndOrgId(id, callerOrgId())
                .map(EvaluationSuiteDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * @summary Creates a new Evaluation Suite.
     * @logic
     * - Validates payload for required fields.
     * - Delegates creation to EvaluationService.
     */
    @PostMapping("/suites")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EvaluationSuiteDTO> createSuite(@RequestBody @Valid CreateSuiteRequest payload) {
        String createdBy = payload.createdBy() == null ? "system" : payload.createdBy();
        EvaluationSuite suite = evaluationService.createSuite(payload.name(), payload.description(), createdBy);
        return ResponseEntity.ok(EvaluationSuiteDTO.from(suite));
    }

    /**
     * @summary Deletes an Evaluation Suite by ID (caller-org scoped).
     * @logic G2 — was {@code existsById} (cross-tenant delete). Cross-tenant id
     *        now 404s with the same shape as a missing id.
     */
    @DeleteMapping("/suites/{suiteId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSuite(@PathVariable String suiteId) {
        if (suiteRepository.existsByIdAndOrgId(suiteId, callerOrgId())) {
            suiteRepository.deleteById(suiteId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // --- Cases ---

    /**
     * @summary Retrieves all Cases that belong to a specific Suite.
     * @logic
     * - Uses custom repository method `findBySuiteId`.
     */
    @GetMapping("/suites/{suiteId}/cases")
    public ResponseEntity<org.springframework.data.domain.Page<EvaluationCaseDTO>> getCasesForSuite(
            @PathVariable String suiteId,
            @org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        // G2 — gate on suite ownership; cross-tenant suiteId returns 404 (same shape
        // as a missing suite). Pre-G2 the controller returned an empty list for
        // unknown suiteIds and a populated list for cross-tenant suites — both
        // information disclosures.
        // G4 — was unbounded List<EvaluationCase>; now Page<EvaluationCase> with the
        // nested-content wire envelope (spring.data.web.pageable.serialization-mode=direct).
        if (!suiteRepository.existsByIdAndOrgId(suiteId, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(caseRepository.findBySuiteId(suiteId, pageable).map(EvaluationCaseDTO::from));
    }

    /**
     * @summary Appends a new Evaluation Case to a Suite.
     * @logic
     * - Validates presence of Name and Input payload fields.
     * - Associates the new Case with the given Suite via the EvaluationService.
     */
    @PostMapping("/suites/{suiteId}/cases")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EvaluationCaseDTO> addCaseToSuite(@PathVariable String suiteId, @RequestBody @Valid AddCaseRequest payload) {
        // G2 — gate on suite ownership; cross-tenant or unknown suiteId → 404 (no
        // existence leak, no allows-mutation-of-other-tenant's-suite vector).
        if (!suiteRepository.existsByIdAndOrgId(suiteId, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        EvaluationCase evalCase = evaluationService.addCaseToSuite(
                suiteId,
                payload.name(),
                payload.input(),
                payload.expectedOutput(),
                payload.systemPromptOverride());
        return ResponseEntity.ok(EvaluationCaseDTO.from(evalCase));
    }

    /**
     * @summary Deletes a specific Evaluation Case by ID.
     * @logic
     * - Validates existence, excises the Case from the DB, returns 204 No Content.
     */
    @DeleteMapping("/cases/{caseId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteCase(@PathVariable String caseId) {
        // G2 — case is only deletable when its parent suite belongs to the caller's
        // org. Cross-tenant case id → 404 (same shape as missing case).
        Optional<EvaluationCase> maybeCase = caseRepository.findById(caseId);
        if (maybeCase.isEmpty()
                || !suiteRepository.existsByIdAndOrgId(maybeCase.get().getSuiteId(), callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        caseRepository.deleteById(caseId);
        return ResponseEntity.noContent().build();
    }

    // --- Runs & Execution ---

    /**
     * @summary Lists historical Runs for a specified Evaluation Suite.
     * @logic
     * - Queries the runRepository filtering by Suite, ordering by start time descending.
     */
    @GetMapping("/suites/{suiteId}/runs")
    public ResponseEntity<org.springframework.data.domain.Page<EvaluationRunDTO>> getRunsForSuite(
            @PathVariable String suiteId,
            @org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        // G2 — gate on suite ownership; cross-tenant suiteId → 404 (pre-G2 would
        // have returned the historical runs of another tenant's suite).
        // G4 — was unbounded List<EvaluationRun>; now Page<EvaluationRun> with the
        // nested-content wire envelope. DESC ordering enforced by the derived-method
        // name on the repository.
        if (!suiteRepository.existsByIdAndOrgId(suiteId, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(runRepository.findBySuiteIdOrderByStartedAtDesc(suiteId, pageable).map(EvaluationRunDTO::from));
    }

    /**
     * @summary Triggers an active execution iteration of an Evaluation Suite against a Target Agent.
     * @logic
     * - Validates agentId presence.
     * - Submits the execution payload to the EvaluationService.
     * - Depending on async configuration, evaluates all cases sequentially or concurrently.
     */
    @PostMapping("/suites/{suiteId}/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> runSuite(@PathVariable String suiteId, @RequestParam String agentId) throws Exception {
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // G2 — gate on suite ownership; pre-G2 a tenant could trigger an EVALUATION_RUN
        // against another tenant's suite. Agent ownership is verified deeper in the
        // job execution path (AgentOperations.run).
        if (!suiteRepository.existsByIdAndOrgId(suiteId, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        String payload = objectMapper.writeValueAsString(new EvaluationRunJobHandler.Payload(suiteId, agentId));
        var job = jobQueueService.enqueue(EvaluationRunJobHandler.JOB_TYPE, agentId, payload, null, null);
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }
    
    /**
     * @summary Retrieves the detailed breakdown of Results within a specific Run execution.
     * @logic
     * - Queries the resultRepository for all outcomes tied to the runId.
     */
    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<List<EvaluationResultDTO>> getRunResults(@PathVariable String runId) {
        // G2 — gate on run → suite → org chain; cross-tenant runId → 404 (no
        // existence leak of per-case scoring data).
        Optional<EvaluationRun> maybeRun = runRepository.findById(runId);
        if (maybeRun.isEmpty()
                || !suiteRepository.existsByIdAndOrgId(maybeRun.get().getSuiteId(), callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(resultRepository.findByRunId(runId).stream()
                .map(EvaluationResultDTO::from).toList());
    }

    // --- Observability Metrics & Feedback ---

    @GetMapping("/metrics")
    public ResponseEntity<AggregateEvalMetrics> getEvaluationMetrics() {
        // G2 — was runRepository.findAll() (cross-tenant aggregate). Filter to runs
        // whose parent suite belongs to caller's org via the JPQL subquery added
        // on the repository.
        List<EvaluationRun> runs = runRepository.findAllInOrg(callerOrgId());
        long totalRuns = runs.size();
        long totalCases = runs.stream().mapToLong(r -> r.getTotalCases() != null ? r.getTotalCases() : 0).sum();
        long passedCases = runs.stream().mapToLong(r -> r.getPassedCases() != null ? r.getPassedCases() : 0).sum();
        long failedCases = runs.stream().mapToLong(r -> r.getFailedCases() != null ? r.getFailedCases() : 0).sum();
        double passRate = totalCases > 0 ? (double) passedCases / totalCases * 100.0 : 0.0;
        OptionalDouble avgScore = runs.stream().filter(r -> r.getAverageScore() != null).mapToDouble(EvaluationRun::getAverageScore).average();
        OptionalDouble avgLatency = runs.stream().filter(r -> r.getAverageLatencyMs() != null).mapToDouble(EvaluationRun::getAverageLatencyMs).average();
        return ResponseEntity.ok(new AggregateEvalMetrics(
            totalRuns, totalCases, passedCases, failedCases,
            Math.round(passRate * 10.0) / 10.0,
            Math.round(avgScore.orElse(0.0) * 100.0) / 100.0,
            Math.round(avgLatency.orElse(0.0) * 10.0) / 10.0
        ));
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody @Valid SubmitFeedbackRequest feedback) {
        log.info("Evaluation feedback received: runId={}, rating={}, comment={}",
            feedback.runId(), feedback.rating(), feedback.comment());
        return ResponseEntity.ok(Map.of("status", "received"));
    }

    /**
     * Resolves the caller's {@code orgId} from {@link AgentContextHolder}, falling back
     * to {@link TenantConstants#DEFAULT_SYSTEM_ORG} when the context is unset (system
     * callers like the background job poller). Mirrors the helper pattern in
     * {@code ScheduleService.callerOrgId} and {@code KnowledgeBaseController.callerOrgId}.
     */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }
}
