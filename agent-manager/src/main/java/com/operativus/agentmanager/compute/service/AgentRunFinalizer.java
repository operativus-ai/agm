package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.MetricConstants;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.RunOperations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain Responsibility: Persists the terminal state of an {@code AgentRun} — status, output,
 *     optional required-action payload, and the flushed {@link RunTelemetryAccumulator} — in a
 *     transaction independent of the caller's. AGM logging §5.13.
 * State: Stateless; holds only a {@link RunOperations} reference.
 *
 * <p><b>Why a separate bean?</b> Spring's transactional proxy does not intercept {@code this.method()}
 * calls; isolating finalization in a distinct component guarantees the {@link Propagation#REQUIRES_NEW}
 * annotation takes effect. Without it, if the caller runs inside a transaction that later rolls back
 * (e.g. an exception escaping {@code AgentService.run()} while finalization sits in its {@code finally}),
 * the terminal status update and telemetry flush would be rolled back too — leaving the run stuck in
 * {@code RUNNING} with null telemetry. This matches Risk R-20 in the logging plan.
 *
 * <p><b>Retry.</b> Swarm parallel branches can update the same {@code AgentRun} row concurrently;
 * a losing {@code @Version} write raises {@link OptimisticLockingFailureException}. Risk R-8 specifies
 * a 3-attempt retry loop — each attempt reloads the row and re-applies the accumulator so we never
 * clobber a newer, higher-version state.
 *
 * <p>If all retries exhaust the accumulator contents are still durable in {@code agent_run_events}
 * (emitted by advisors on each LLM call, once §5.13 writer wiring lands), so a repair job can
 * reconstruct telemetry from that table without running this bean again.
 */
@Component
public class AgentRunFinalizer {

    private static final Logger log = LoggerFactory.getLogger(AgentRunFinalizer.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RunOperations runRepository;
    private final MeterRegistry meterRegistry;

    public AgentRunFinalizer(RunOperations runRepository, MeterRegistry meterRegistry) {
        this.runRepository = runRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Finalizes {@code runId} by reloading a fresh entity, applying terminal fields + accumulated
     * telemetry, and saving under {@link Propagation#REQUIRES_NEW}. Retries up to 3 times on
     * optimistic-lock conflicts. Never throws — flush failure logs a warning and returns.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeRun(String runId,
                            RunStatus terminalStatus,
                            String output,
                            String requiredAction,
                            RunTelemetryAccumulator accumulator) {
        if (runId == null) return;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var opt = runRepository.findByIdForUpdate(runId);
                if (opt.isEmpty()) {
                    log.warn("agent.run.finalize.not-found runId={}", runId);
                    return;
                }
                AgentRun fresh = opt.get();

                // Terminal-state idempotence guard: if a concurrent writer (resume vs. sweeper,
                // success vs. cancel, etc.) has already finalized this row, do NOT overwrite it.
                // finalizeRun's contract is "transition to terminal", not "force terminal" — a
                // row already in COMPLETED / FAILED / CANCELLED is not a finalization target.
                // Without this guard, retry-after-OptimisticLock reload silently clobbered the
                // earlier writer's terminal state. PAUSED is intentionally NOT terminal — the
                // resume path's PAUSED → COMPLETED/CANCELLED transition must proceed.
                if (isAlreadyTerminal(fresh.getStatus())) {
                    log.debug("agent.run.finalize.already-terminal runId={} existingStatus={} requestedStatus={}",
                            runId, fresh.getStatus(), terminalStatus);
                    return;
                }

                if (terminalStatus != null) fresh.setStatus(terminalStatus);
                if (output != null) fresh.setOutput(output);
                if (requiredAction != null) fresh.setRequiredAction(requiredAction);
                if (accumulator != null) accumulator.applyTo(fresh);

                runRepository.save(fresh);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("agent.run.finalize.lock-exhausted runId={} attempts={} status={}",
                            runId, attempt, terminalStatus, e);
                    Counter.builder(MetricConstants.HITL_FINALIZE_LOCK_EXHAUSTED_TOTAL)
                            .tag("status", terminalStatus == null ? "n/a" : terminalStatus.name())
                            .description("AgentRunFinalizer's 3-attempt optimistic-lock retry exhausted; "
                                    + "the requested terminal write was lost. Cross-pod contention indicator.")
                            .register(meterRegistry)
                            .increment();
                    return;
                }
                log.debug("agent.run.finalize.lock-retry runId={} attempt={}", runId, attempt);
            } catch (RuntimeException e) {
                log.warn("agent.run.finalize.failed runId={} status={}", runId, terminalStatus, e);
                return;
            }
        }
    }

    private static boolean isAlreadyTerminal(RunStatus s) {
        return s == RunStatus.COMPLETED || s == RunStatus.FAILED || s == RunStatus.CANCELLED;
    }
}
