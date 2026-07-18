package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.control.repository.ApprovalRepository;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.entity.Approval;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.RunOperations;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Domain Responsibility: Manages the lifecycle of background agent executions — submission, tracking, cancellation, and orphan cleanup.
 * State: Stateful (activeRuns map)
 */
@Service
public class RunExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionManager.class);

    private final RunOperations runRepository;
    private final Tracer tracer;
    private final AgentRunFinalizer agentRunFinalizer;
    private final ApprovalRepository approvalRepository;
    private final MeterRegistry meterRegistry;
    private final AgentRunEventBus agentRunEventBus;
    private final long stuckPausedCutoffHours;
    private final Map<String, Future<?>> activeRuns = new ConcurrentHashMap<>();
    // Per-runId cancellation sinks for reactive (streaming) runs. Streaming runs do NOT
    // register in `activeRuns` (no virtual thread to interrupt) — the SSE Flux subscribes
    // on Reactor's I/O scheduler. AgentStreamManager registers a sink for each stream and
    // composes the Flux with takeUntilOther(sink.asMono()); cancel(runId) emits on the sink
    // so the Flux terminates as soon as the user clicks Stop, instead of running to natural
    // completion after the agent_runs row is already CANCELLED.
    private final Map<String, Sinks.Empty<Void>> streamingCancellationSinks = new ConcurrentHashMap<>();

    public RunExecutionManager(RunOperations runRepository, Tracer tracer, AgentRunFinalizer agentRunFinalizer,
                               ApprovalRepository approvalRepository, MeterRegistry meterRegistry,
                               AgentRunEventBus agentRunEventBus,
                               @Value("${agentmanager.scheduler.run-paused-cutoff-hours:24}") long stuckPausedCutoffHours) {
        this.runRepository = runRepository;
        this.tracer = tracer;
        this.agentRunFinalizer = agentRunFinalizer;
        this.approvalRepository = approvalRepository;
        this.meterRegistry = meterRegistry;
        this.agentRunEventBus = agentRunEventBus;
        this.stuckPausedCutoffHours = stuckPausedCutoffHours;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOrphanedRuns() {
        log.info("Startup check: Scanning for orphaned AgentRun records...");
        java.util.List<AgentRun> stuckRuns = runRepository.findByStatusIn(
            java.util.List.of(RunStatus.RUNNING, RunStatus.QUEUED));

        int count = 0;
        for (AgentRun run : stuckRuns) {
            if (!activeRuns.containsKey(run.getId())) {
                run.setStatus(RunStatus.CANCELLED);
                run.setOutput("Execution cancelled due to application restart (Orphaned).");
                runRepository.save(run);
                publishRunCancelled(run, StuckPauseClassification.ORPHANED_ON_RESTART);
                count++;
            }
        }
        if (count > 0) {
            log.warn("Cleaned up {} orphaned runs that outlived the previous server process.", count);
        } else {
            log.info("No orphaned runs found. System state is clean.");
        }
    }

    /**
     * @summary Garbage Collector: hourly sweep that finalizes {@code RunStatus.PAUSED} rows whose
     *          HITL approval was never resolved. Mirrors {@code ApprovalService.expireStaleApprovals}
     *          on the run side — without this, an abandoned approval leaves the agent_runs row
     *          stuck in PAUSED forever, blocking session UI / billing rollups / audit closure.
     * @logic Loads PAUSED runs older than {@code agentmanager.scheduler.run-paused-cutoff-hours}
     *        (default 24h) via a single SQL range scan against the composite index
     *        {@code idx_agent_runs_status_created_at} (changeset 054), then routes each through
     *        {@link AgentRunFinalizer#finalizeRun} so the @Version write retries on contention
     *        and never clobbers a concurrent resume that just landed. Each cancelled row is
     *        classified by approval state (Tier 2.4 PR 7 F-B) so operators triaging stuck runs
     *        from logs/dashboards can distinguish upstream-expired from user-abandoned from
     *        resume-failed. RUN_CANCELLED lifecycle event publishes downstream signals.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.run-paused-cleanup-ms:3600000}")
    public void expireStuckPausedRuns() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(stuckPausedCutoffHours);
        java.util.List<AgentRun> stuck = runRepository.findByStatusInAndCreatedAtBefore(
                java.util.List.of(RunStatus.PAUSED), cutoff);

        if (stuck.isEmpty()) {
            log.debug("Stuck-PAUSED sweep: no PAUSED runs older than {}h.", stuckPausedCutoffHours);
            return;
        }

        log.warn("Stuck-PAUSED sweep: cancelling {} run(s) PAUSED >= {}h.", stuck.size(), stuckPausedCutoffHours);
        for (AgentRun run : stuck) {
            StuckPauseClassification cls = classifyStuckRun(run);
            String reason = "Stuck-PAUSED scheduler cancellation (" + cls.label() + "): "
                    + cls.detail() + " Exceeded " + stuckPausedCutoffHours + "h pause cutoff.";
            agentRunFinalizer.finalizeRun(run.getId(), RunStatus.CANCELLED, reason, null, null);

            meterRegistry.counter("agm.runs.stuck_paused_cancelled_total",
                    "classification", cls.label()).increment();
            publishRunCancelled(run, cls);
        }
    }

    /**
     * Tier 2.4 PR 7 F-B — classifies WHY a stuck-PAUSED run is being cancelled by examining
     * the most recent corresponding approval row. Used for operator triage signal in metrics
     * and logs; doesn't affect the cancellation outcome itself.
     */
    private StuckPauseClassification classifyStuckRun(AgentRun run) {
        return approvalRepository.findFirstByRunIdOrderByCreatedAtDesc(run.getId())
                .map(approval -> classifyByApprovalStatus(approval, run))
                .orElse(StuckPauseClassification.NO_APPROVAL_ROW);
    }

    private StuckPauseClassification classifyByApprovalStatus(Approval approval, AgentRun run) {
        RunStatus status = approval.getStatus();
        if (status == RunStatus.EXPIRED) {
            return StuckPauseClassification.APPROVAL_EXPIRED_UPSTREAM;
        }
        if (status == RunStatus.PENDING) {
            // Workflow link is on the approval (set from AgentContextHolder.getWorkflowRunId at
            // approval creation time, not duplicated on agent_runs). Distinguish workflow-stalled
            // from single-agent user-abandoned for clearer triage signal.
            return approval.getWorkflowRunId() != null
                    ? StuckPauseClassification.WORKFLOW_STEP_ABANDONED
                    : StuckPauseClassification.USER_ABANDONED;
        }
        if (status == RunStatus.APPROVED || status == RunStatus.REJECTED) {
            return StuckPauseClassification.RESUME_FAILED_AFTER_RESOLVE;
        }
        return StuckPauseClassification.UNKNOWN;
    }

    private void publishRunCancelled(AgentRun run, StuckPauseClassification cls) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("classification", cls.label());
            payload.put("agentId", run.getAgentId());
            agentRunEventBus.publish(new AgentRunEvent(
                    AgentRunEventType.RUN_CANCELLED,
                    run.getId(),
                    run.getAgentId(),
                    run.getParentRunId(),
                    run.getSessionId(),
                    run.getOrgId(),
                    null,
                    payload,
                    Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("Failed to publish RUN_CANCELLED event runId={}", run.getId(), ex);
        }
    }

    /**
     * @summary Submits a pre-created AgentRun onto a Virtual Thread for background execution.
     * @logic Marks the row RUNNING, runs the supplied {@link Supplier} (which is {@code AgentService.run()}),
     *        and registers the Future for cancellation. Terminal-state persistence (COMPLETED / PAUSED / FAILED
     *        with telemetry + requiredAction) is owned by {@link AgentRunFinalizer#finalizeRun} which
     *        {@code AgentService.run()} invokes on every post-try-block exit path. If the supplier throws
     *        before reaching that try block (pre-validation: agent missing, concurrency cap, maintenance,
     *        rate-limit, depth, repo errors), the finalizer is never invoked and the row would otherwise
     *        be stuck at RUNNING. The catch below is the safety net for that two cases:
     *        <ol>
     *          <li><b>Pre-execution validation failure</b> — finalize as {@code FAILED}.</li>
     *          <li><b>User-initiated cancel via {@code RunExecutionManager.cancel}</b> — when
     *              {@code Future.cancel(true)} interrupts the VT, the interrupt unwinds back to
     *              this catch as either an {@code InterruptedException} (or wrapped equivalent)
     *              or with {@code Thread.currentThread().isInterrupted() == true}. Two issues
     *              this branch fixes:
     *              <ul>
     *                <li>Bug A — without this branch, cancelled runs were finalized as
     *                    {@code FAILED} not {@code CANCELLED}, polluting failure metrics and
     *                    losing the user-cancel-vs-error distinction.</li>
     *                <li>Bug B — without {@link Thread#interrupted()} clearing the flag,
     *                    {@code AgentRunFinalizer.finalizeRun}'s {@code @Transactional} JDBC
     *                    write rolled back on the still-interrupted thread (logs:
     *                    "Application exception overridden by rollback exception"), leaving
     *                    the row stuck at {@code RUNNING} until the orphan sweeper rescued
     *                    it at next app restart.</li>
     *              </ul>
     *          </li>
     *        </ol>
     *        Both branches preserve the original "only finalize if still non-terminal" guard so
     *        we never race a finalizer write that already succeeded.
     */
    public String submit(AgentRun run, Supplier<RunResponse> execution) {
        Runnable task = ContextSnapshotFactory.builder().build().captureAll().wrap(() -> {
            Span bgSpan = tracer.nextSpan().name("AgentService.runInBackground").tag("runId", run.getId()).start();
            try (Tracer.SpanInScope ws = tracer.withSpan(bgSpan)) {
                log.info("Executing background task for run ID: {}", run.getId());
                run.setStatus(RunStatus.RUNNING);
                runRepository.save(run);

                execution.get();
            } catch (Exception e) {
                bgSpan.error(e);

                // Detect cancellation. Two signals are both checked because either alone is
                // unreliable across the variety of code paths an interrupt can take:
                //   - Thread.interrupted() returns true if the VT's interrupt flag is still
                //     set; SIDE EFFECT: clears the flag, which is required for the finalizer's
                //     downstream JDBC write to commit (otherwise the new transaction's first
                //     interruptible operation aborts and the row stays RUNNING — Bug B).
                //   - hasInterruptedCause(e) walks the cause chain because some interrupt
                //     paths re-throw via RuntimeException wrappers that may have already
                //     reset the thread interrupt flag before reaching here.
                boolean threadWasInterrupted = Thread.interrupted();
                boolean exceptionCarriesInterrupt = hasInterruptedCause(e);
                boolean wasCancelled = threadWasInterrupted || exceptionCarriesInterrupt;

                if (wasCancelled) {
                    log.info("[background] run {} terminated by cancel (threadFlag={}, exceptionCause={})",
                            run.getId(), threadWasInterrupted, exceptionCarriesInterrupt);
                } else {
                    log.warn("[background] run {} terminated with exception", run.getId(), e);
                }

                final RunStatus terminalStatus = wasCancelled ? RunStatus.CANCELLED : RunStatus.FAILED;
                final String reason = wasCancelled
                        ? "Cancelled by user"
                        : "Pre-execution validation failed: " + e.getMessage();

                runRepository.findById(run.getId()).ifPresent(r -> {
                    if (r.getStatus() != RunStatus.COMPLETED
                            && r.getStatus() != RunStatus.FAILED
                            && r.getStatus() != RunStatus.CANCELLED) {
                        agentRunFinalizer.finalizeRun(run.getId(), terminalStatus, reason, null, null);
                    }
                });
            } finally {
                activeRuns.remove(run.getId());
                bgSpan.end();
            }
        });

        Future<?> future = Executors.newVirtualThreadPerTaskExecutor().submit(task);
        activeRuns.put(run.getId(), future);
        return run.getId();
    }

    /**
     * Walks the exception cause chain looking for an {@link InterruptedException} or
     * {@link java.util.concurrent.CancellationException}. Returns true if either is
     * present. Caps at 16 levels to bound traversal time on degenerate cause cycles.
     */
    private static boolean hasInterruptedCause(Throwable t) {
        for (int i = 0; t != null && i < 16; i++) {
            if (t instanceof InterruptedException) return true;
            if (t instanceof java.util.concurrent.CancellationException) return true;
            Throwable next = t.getCause();
            if (next == t) return false;
            t = next;
        }
        return false;
    }

    public void cancel(String runId) {
        // Streaming Flux cancellation: emit on the sink BEFORE the Future branch. The
        // streaming path does NOT register in activeRuns; it registers a sink keyed by
        // runId. Without this signal the Flux runs to natural completion and the SSE
        // emits CONTENT_DELTA + STOP even though the agent_runs row is already CANCELLED.
        Sinks.Empty<Void> sink = streamingCancellationSinks.remove(runId);
        if (sink != null) {
            sink.tryEmitEmpty();
        }
        Future<?> future = activeRuns.remove(runId);
        if (future != null) {
            // Interrupt the virtual thread; AgentService.run's outer catch invokes
            // AgentRunFinalizer.finalizeRun on InterruptedException, which is the
            // contract owner. Writing CANCELLED here directly would race that
            // @Version-checked write and lose silently — see submit() Javadoc.
            future.cancel(true);
            return;
        }
        runRepository.findById(runId).ifPresent(run -> {
            if (run.getStatus() != RunStatus.COMPLETED && run.getStatus() != RunStatus.FAILED) {
                agentRunFinalizer.finalizeRun(runId, RunStatus.CANCELLED, "Cancelled by user", null, null);
                publishRunCancelled(run, StuckPauseClassification.USER_INITIATED);
            }
        });
    }

    /**
     * Registers a cancellation signal for a streaming run. The returned Mono completes
     * (without a value) when {@link #cancel(String)} fires for {@code runId}. Pair with
     * {@link #unregisterStreamingCancellationSignal(String)} in a {@code doFinally} on the
     * stream's terminal Flux so the map doesn't leak when the stream completes naturally.
     *
     * <p>Composition pattern in AgentStreamManager:
     * <pre>
     *   Mono&lt;Void&gt; cancelMono = runExecutionManager.registerStreamingCancellationSignal(runId);
     *   return Flux.concat(...)
     *           .takeUntilOther(cancelMono)
     *           .doFinally(sig -&gt; runExecutionManager.unregisterStreamingCancellationSignal(runId));
     * </pre>
     */
    public Mono<Void> registerStreamingCancellationSignal(String runId) {
        Sinks.Empty<Void> sink = Sinks.empty();
        // putIfAbsent so a duplicate register on the same runId (e.g. resume path) reuses
        // the existing sink — both subscribers terminate on the same cancel event.
        Sinks.Empty<Void> prior = streamingCancellationSinks.putIfAbsent(runId, sink);
        return (prior != null ? prior : sink).asMono();
    }

    public void unregisterStreamingCancellationSignal(String runId) {
        streamingCancellationSinks.remove(runId);
    }

    /**
     * Tier 2.4 PR 7 F-B — discrete classifications for cancelled runs. Surfaces the WHY
     * in cancellation reason text, the {@code agm.runs.stuck_paused_cancelled_total}
     * metric label, and the RUN_CANCELLED event payload.
     */
    enum StuckPauseClassification {
        APPROVAL_EXPIRED_UPSTREAM("approval_expired_upstream",
                "Corresponding approval already EXPIRED via SLA garbage collector."),
        USER_ABANDONED("user_abandoned",
                "Corresponding approval still PENDING; user did not act within the SLA."),
        WORKFLOW_STEP_ABANDONED("workflow_step_abandoned",
                "Workflow step paused on approval, still PENDING; workflow may have stalled."),
        RESUME_FAILED_AFTER_RESOLVE("resume_failed_after_resolve",
                "Approval was resolved but agent run never completed the resume — investigate."),
        NO_APPROVAL_ROW("no_approval_row",
                "No approval row found for this run; row may have been deleted."),
        ORPHANED_ON_RESTART("orphaned_on_restart",
                "Run was RUNNING/QUEUED at app shutdown; no resume mechanism."),
        USER_INITIATED("user_initiated",
                "Cancelled directly by user via cancel API."),
        UNKNOWN("unknown",
                "Approval row in unexpected state.");

        private final String label;
        private final String detail;

        StuckPauseClassification(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }

        String label() { return label; }
        String detail() { return detail; }
    }
}
