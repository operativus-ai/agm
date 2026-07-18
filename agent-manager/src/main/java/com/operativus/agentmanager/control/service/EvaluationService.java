package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.EvaluationCase;
import com.operativus.agentmanager.core.entity.EvaluationResult;
import com.operativus.agentmanager.core.entity.EvaluationRun;
import com.operativus.agentmanager.core.entity.EvaluationSuite;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.control.repository.EvaluationCaseRepository;
import com.operativus.agentmanager.control.repository.EvaluationResultRepository;
import com.operativus.agentmanager.control.repository.EvaluationRunRepository;
import com.operativus.agentmanager.control.repository.EvaluationSuiteRepository;
import com.operativus.agentmanager.control.service.scoring.Scorer;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.registry.EvaluationOperations;
import com.operativus.agentmanager.core.registry.StreamRegistry;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.EventType;
import com.operativus.agentmanager.core.model.enums.RunStatus;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.retry.support.RetryTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Orchestrates the execution of test suites against agents to evaluate their performance.
 * State: Stateless
 */
@Service
public class EvaluationService implements EvaluationOperations {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final EvaluationSuiteRepository suiteRepository;
    private final EvaluationCaseRepository caseRepository;
    private final EvaluationRunRepository runRepository;
    private final EvaluationResultRepository resultRepository;
    private final AgentOperations agentService;
    private final Map<String, Scorer> scorers;
    
    private final StreamRegistry streamRegistry;
    private final RateLimiter rateLimiter;
    private final RetryTemplate retryTemplate;
    private final TransactionTemplate txTemplate;

    public EvaluationService(EvaluationSuiteRepository suiteRepository,
                             EvaluationCaseRepository caseRepository,
                             EvaluationRunRepository runRepository,
                             EvaluationResultRepository resultRepository,
                             AgentOperations agentService,
                             Map<String, Scorer> scorers,
                             StreamRegistry streamRegistry,
                             RateLimiterRegistry rateLimiterRegistry,
                             PlatformTransactionManager txManager) {
        this.suiteRepository = suiteRepository;
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.agentService = agentService;
        this.scorers = scorers;
        this.streamRegistry = streamRegistry;
        this.rateLimiter = rateLimiterRegistry.rateLimiter("llmEvaluations");
        this.txTemplate = new TransactionTemplate(txManager);

        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(1000, 2, 10000)
                .build();
    }

    /**
     * @summary Creates a new evaluation suite.
     * @logic Instantiates an EvaluationSuite with a generated UUID, provided name,
     *        description, and creator. G2 — stamps {@code orgId} from
     *        {@code AgentContextHolder.getOrgId()} (or {@code DEFAULT_SYSTEM_ORG} for
     *        system callers) so the row is tenant-scoped on every subsequent read.
     */
    @Transactional
    public EvaluationSuite createSuite(String name, String description, String createdBy) {
        EvaluationSuite suite = new EvaluationSuite(UUID.randomUUID().toString(), name, description, createdBy);
        String orgId = com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId();
        suite.setOrgId((orgId == null || orgId.isBlank())
                ? com.operativus.agentmanager.core.model.TenantConstants.DEFAULT_SYSTEM_ORG
                : orgId);
        return suiteRepository.save(suite);
    }

    /**
     * @summary Adds a new test case to an existing evaluation suite.
     * @logic Instantiates an EvaluationCase with a generated UUID and associates it with the suite ID, then persists it.
     */
    @Transactional
    public EvaluationCase addCaseToSuite(String suiteId, String name, String input, String expectedOutput, String promptOverride) {
        EvaluationCase evalCase = new EvaluationCase(UUID.randomUUID().toString(), suiteId, name, input, expectedOutput, promptOverride);
        return caseRepository.save(evalCase);
    }

    /**
     * @summary Initiates the execution of an evaluation suite against a specific agent.
     * @logic Validates the suite and its cases, creates an EvaluationRun in 'IN_PROGRESS' state, and spawns a background Virtual Thread to process the cases asynchronously via executeEvaluationRun.
     */
    @Transactional
    public EvaluationRun runSuite(String suiteId, String agentId) {
        suiteRepository.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation Suite not found: " + suiteId));

        List<EvaluationCase> cases = caseRepository.findBySuiteId(suiteId);
        if (cases.isEmpty()) {
            throw new IllegalStateException("Cannot run an empty suite.");
        }

        EvaluationRun run = new EvaluationRun(UUID.randomUUID().toString(), suiteId, agentId, RunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setTotalCases(cases.size());

        EvaluationRun savedRun = runRepository.save(run);

        // F6 — fresh virtual threads do NOT inherit JDK 21 ScopedValues. Capture the caller
        // thread's AgentContextHolder bindings (orgId, userId, runId, telemetry, …) before the
        // fan-out so the outer VT, the inner per-case VTs, and AgentService.run inside each
        // case all see the same tenant context. Without this, the strict-orgId
        // AgentRegistry.findById fails to resolve the agent and the run never finalizes.
        final com.operativus.agentmanager.core.callback.AgentContextSnapshot snapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();

        // Defer VT spawn until AFTER the surrounding @Transactional commits — otherwise the VT
        // races the parent transaction and runRepository.findById(runId) on the VT side returns
        // empty (READ COMMITTED can't see uncommitted writes from another connection).
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        Thread.ofVirtual().start(() -> snapshot.run(() ->
                                executeEvaluationRun(savedRun.getId(), agentId, suiteId, snapshot)));
                    }
                });

        return savedRun;
    }

    /**
     * @summary Processes all test cases in an evaluation suite concurrently.
     * @logic Streams initialization events, provisions a VirtualThreadPerTaskExecutor to run cases in parallel, publishes real-time progress deltas to the stream registry, and handles unanticipated pipeline failures.
     */
    private void executeEvaluationRun(String runId, String agentId, String suiteId,
            com.operativus.agentmanager.core.callback.AgentContextSnapshot snapshot) {

        List<EvaluationCase> cases = caseRepository.findBySuiteId(suiteId);
        EvaluationRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;

        log.info("Starting Async Evaluation Run [{}] for Agent [{}] across {} cases using Virtual Threads.", run.getId(), agentId, cases.size());

        streamRegistry.publishEvent(runId, new AgentStreamEvent(EventType.START, "Initializing Evaluation Suite...", System.currentTimeMillis()));

        Map<String, EvaluationResult> tempResults = new ConcurrentHashMap<>();
        int completedCount = 0;

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // Each per-case VT is also a fresh thread that does NOT inherit ScopedValues from
            // its parent VT — JDK 21 only propagates via StructuredTaskScope, not via the
            // executor.submit path. Rebind the captured snapshot inside every submit lambda.
            List<java.util.concurrent.Future<EvaluationResult>> futures = cases.stream()
                .map(evalCase -> executor.submit(() -> snapshot.call(() ->
                        executeSingleCase(runId, agentId, evalCase, tempResults))))
                .toList();

            for (var future : futures) {
                try {
                    EvaluationResult res = future.get();
                    completedCount++;
                    // Stream real-time progress update
                    String progressPayload = String.format("{\"completed\": %d, \"total\": %d, \"caseId\": \"%s\", \"passed\": %b, \"score\": %f}", 
                        completedCount, cases.size(), res.getCaseId(), res.getIsPassing(), res.getScore());
                    streamRegistry.publishEvent(runId, new AgentStreamEvent(EventType.CONTENT_DELTA, progressPayload, System.currentTimeMillis()));
                } catch (Exception e) {
                    log.error("A subtask failed during evaluation run {}", runId, e);
                }
            }
        } catch (Exception e) {
            log.error("Evaluation run failed unexpectedly", e);
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage("Unexpected failure: " + e.getMessage());
            streamRegistry.publishEvent(runId, new AgentStreamEvent(EventType.ERROR, run.getErrorMessage(), System.currentTimeMillis()));
        }

        // Use TransactionTemplate explicitly — this is an internal call from the spawned VT
        // context (no Spring proxy in scope), so the @Transactional annotation on
        // finalizeAndSaveRunMetrics would NOT engage. Without an active transaction, the
        // runRepository.save() at the end never flushes and evaluation_runs.status stays
        // RUNNING. (Even with the deferred VT spawn fixing the race against the parent commit,
        // the @Transactional self-call would still no-op without this template.)
        txTemplate.execute(status -> {
            finalizeAndSaveRunMetrics(runId, tempResults.values());
            return null;
        });
        streamRegistry.publishEvent(runId, new AgentStreamEvent(EventType.STOP, "Evaluation Complete.", System.currentTimeMillis()));
        streamRegistry.complete(runId);
    }

    /**
     * @summary Executes a single evaluation case and scores the agent's output.
     * @logic Formats a dedicated session ID, synchronously invokes the agent mapped with rate-limit and retry resiliency, delegates actual vs. expected output to a dynamically selected Scorer, and records metrics (latency, outcome).
     */
    private EvaluationResult executeSingleCase(String runId, String agentId, EvaluationCase evalCase, Map<String, EvaluationResult> resultsMap) {
        EvaluationResult result = new EvaluationResult(UUID.randomUUID().toString(), runId, evalCase.getId());
        long startTime = System.currentTimeMillis();

        try {
            String sessionId = "eval-" + runId + "-" + evalCase.getId();
            String prompt = evalCase.getInput();
            // Read directly from AgentContextHolder — the snapshot rebind in
            // executeEvaluationRun's submit lambda has these bound on this thread.
            String orgId = com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId();

            // Protected invocation with Spring Retry and Resilience4j limits to avoid 429
            RunResponse response = retryTemplate.execute(context -> {
                Callable<RunResponse> call = () -> {
                    return agentService.run(agentId, prompt, null, sessionId, "system", orgId, false, null);
                };
                return RateLimiter.decorateCallable(rateLimiter, call).call();
            });
            
            result.setActualOutput(response.content());
            result.setLatencyMs(System.currentTimeMillis() - startTime);

            Scorer activeScorer = selectScorer(evalCase);
            Scorer.ScorerResult scoreResult = activeScorer.evaluate(evalCase, response.content());
            
            result.setScore(scoreResult.score());
            result.setIsPassing(scoreResult.passed());
            result.setErrorMessage(scoreResult.reasoning());

        } catch (Exception e) {
            log.error("Error executing evaluation case {}: {}", evalCase.getId(), e.getMessage());
            result.setActualOutput("");
            result.setScore(0.0);
            result.setIsPassing(false);
            result.setLatencyMs(System.currentTimeMillis() - startTime);
            result.setErrorMessage("Execution failed: " + e.getMessage());
        }

        resultsMap.put(evalCase.getId(), result);
        // Same proxy-bypass concern as finalizeAndSaveRunMetrics: this is an internal call from
        // a spawned VT and the @Transactional on saveGranularResult would NOT engage. Wrap in
        // an explicit TransactionTemplate so the per-case row commits independently and keeps
        // the Hikari CP healthy under parallel execution.
        txTemplate.execute(status -> {
            saveGranularResult(result);
            return null;
        });
        return result;
    }

    /**
     * @summary Persists an individual evaluation result.
     * @logic Saves the result entity using a dedicated transaction to isolate Hikari connection pool usage during highly parallel execution.
     */
    @Transactional
    public void saveGranularResult(EvaluationResult result) {
        resultRepository.save(result);
    }

    /**
     * @summary Aggregates results to finalize an evaluation run's metrics.
     * @logic Iterates over all completed case results to calculate average accuracy scores and latency, marks the run as 'COMPLETED' (unless failed), and updates its timestamp.
     */
    @Transactional
    public void finalizeAndSaveRunMetrics(String runId, java.util.Collection<EvaluationResult> results) {
        EvaluationRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        
        if (run.getStatus() != RunStatus.FAILED) {
            int passed = 0;
            int failed = 0;
            double totalScore = 0.0;
            long totalLatency = 0;

            for (EvaluationResult res : results) {
                if (Boolean.TRUE.equals(res.getIsPassing())) {
                    passed++;
                } else {
                    failed++;
                }
                if (res.getScore() != null) totalScore += res.getScore();
                if (res.getLatencyMs() != null) totalLatency += res.getLatencyMs();
            }

            run.setPassedCases(passed);
            run.setFailedCases(failed);
            if (!results.isEmpty()) {
                run.setAverageScore(totalScore / results.size());
                run.setAverageLatencyMs(totalLatency / results.size());
            }
            
            run.setStatus(RunStatus.COMPLETED);
        }
        
        run.setCompletedAt(LocalDateTime.now());
        runRepository.save(run);
    }

    /**
     * @summary Selects the appropriate scoring strategy for an evaluation case.
     * @logic Resolves the scorer based on expected output patterns (e.g., regex checks) or availability of advanced semantic/LLM-as-a-judge scorers, falling back to an exact match strategy.
     */
    private Scorer selectScorer(EvaluationCase evalCase) {
        if (evalCase.getExpectedOutput() != null && evalCase.getExpectedOutput().startsWith("^") && evalCase.getExpectedOutput().endsWith("$")) {
             return scorers.get("regexMatchScorer");
        }
        if (scorers.containsKey("llmJudgeScorer")) {
            return scorers.get("llmJudgeScorer");
        } else if (scorers.containsKey("semanticScorer")) {
             return scorers.get("semanticScorer");
        }
        return scorers.get("exactMatchScorer");
    }
}
