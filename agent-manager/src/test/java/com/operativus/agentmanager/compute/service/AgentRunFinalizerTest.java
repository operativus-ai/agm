package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.MetricConstants;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.RunOperations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentRunFinalizerTest {

    @Mock
    private RunOperations runRepository;

    private MeterRegistry meterRegistry;
    private AgentRunFinalizer finalizer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        finalizer = new AgentRunFinalizer(runRepository, meterRegistry);
    }

    @Test
    void finalizeRun_appliesTerminalStatusAndAccumulatorOnFreshEntity() {
        AgentRun fresh = new AgentRun();
        fresh.setId("run-1");
        fresh.setStatus(RunStatus.RUNNING);
        when(runRepository.findByIdForUpdate("run-1")).thenReturn(Optional.of(fresh));

        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        acc.addInputTokens(100);
        acc.addOutputTokens(50);
        acc.setModelIfAbsent("gpt-4");

        finalizer.finalizeRun("run-1", RunStatus.COMPLETED, "output text", null, acc);

        ArgumentCaptor<AgentRun> cap = ArgumentCaptor.forClass(AgentRun.class);
        verify(runRepository).save(cap.capture());
        AgentRun saved = cap.getValue();
        assertEquals(RunStatus.COMPLETED, saved.getStatus());
        assertEquals("output text", saved.getOutput());
        assertEquals(100L, saved.getInputTokens());
        assertEquals(50L, saved.getOutputTokens());
        assertEquals("gpt-4", saved.getModel());
    }

    @Test
    void finalizeRun_retriesOnOptimisticLockAndEventuallySucceeds() {
        // T004b note: the production code calls fresh.setStatus(...) on the loaded entity.
        // Using a single shared instance via thenReturn(Optional.of(fresh)) leaks the mutation
        // across mock invocations and the new terminal-state guard correctly short-circuits
        // on retry. Real DB behavior is a fresh hydrate per findById — mirror that with
        // thenAnswer so each retry sees a non-terminal RUNNING row, exercising the retry path.
        when(runRepository.findByIdForUpdate("run-2")).thenAnswer(inv -> {
            AgentRun fresh = new AgentRun();
            fresh.setId("run-2");
            fresh.setStatus(RunStatus.RUNNING);
            return Optional.of(fresh);
        });
        when(runRepository.save(any(AgentRun.class)))
                .thenThrow(new OptimisticLockingFailureException("v1"))
                .thenThrow(new OptimisticLockingFailureException("v2"))
                .thenAnswer(inv -> inv.getArgument(0));

        finalizer.finalizeRun("run-2", RunStatus.COMPLETED, "ok", null, new RunTelemetryAccumulator());

        verify(runRepository, times(3)).findByIdForUpdate("run-2");
        verify(runRepository, times(3)).save(any(AgentRun.class));
    }

    @Test
    void finalizeRun_givesUpAfterThreeLockFailures() {
        // Same fresh-per-findById pattern as above — the new terminal-state guard relies on
        // observing the row's CURRENT persisted status, not a stale mutation from a prior
        // attempt of this same call.
        when(runRepository.findByIdForUpdate("run-3")).thenAnswer(inv -> {
            AgentRun fresh = new AgentRun();
            fresh.setId("run-3");
            fresh.setStatus(RunStatus.RUNNING);
            return Optional.of(fresh);
        });
        when(runRepository.save(any(AgentRun.class)))
                .thenThrow(new OptimisticLockingFailureException("v1"))
                .thenThrow(new OptimisticLockingFailureException("v2"))
                .thenThrow(new OptimisticLockingFailureException("v3"));

        // Must not throw — logs warning and returns.
        assertDoesNotThrow(() -> finalizer.finalizeRun("run-3", RunStatus.FAILED,
                null, null, new RunTelemetryAccumulator()));

        verify(runRepository, times(3)).save(any(AgentRun.class));
    }

    @Test
    void finalizeRun_missingRunIsLoggedAndSwallowed() {
        when(runRepository.findByIdForUpdate("missing")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> finalizer.finalizeRun("missing", RunStatus.COMPLETED,
                "x", null, new RunTelemetryAccumulator()));

        verify(runRepository, never()).save(any());
    }

    @Test
    void finalizeRun_nullRunIdIsNoop() {
        finalizer.finalizeRun(null, RunStatus.COMPLETED, "x", null, new RunTelemetryAccumulator());

        verify(runRepository, never()).findByIdForUpdate(any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void finalizeRun_unexpectedRuntimeExceptionIsSwallowed() {
        when(runRepository.findByIdForUpdate("run-4"))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> finalizer.finalizeRun("run-4", RunStatus.FAILED,
                null, null, new RunTelemetryAccumulator()));

        verify(runRepository, never()).save(any());
    }

    @Test
    void finalizeRun_setsRequiredActionWhenProvided() {
        AgentRun fresh = new AgentRun();
        fresh.setId("run-5");
        when(runRepository.findByIdForUpdate("run-5")).thenReturn(Optional.of(fresh));

        finalizer.finalizeRun("run-5", RunStatus.PAUSED, "paused", "TOOL_APPROVAL", null);

        ArgumentCaptor<AgentRun> cap = ArgumentCaptor.forClass(AgentRun.class);
        verify(runRepository).save(cap.capture());
        assertEquals("TOOL_APPROVAL", cap.getValue().getRequiredAction());
        assertEquals(RunStatus.PAUSED, cap.getValue().getStatus());
    }

    @Test
    void finalizeRun_nullAccumulatorStillFlushesTerminalFields() {
        AgentRun fresh = new AgentRun();
        fresh.setId("run-6");
        when(runRepository.findByIdForUpdate("run-6")).thenReturn(Optional.of(fresh));

        finalizer.finalizeRun("run-6", RunStatus.COMPLETED, "done", null, null);

        verify(runRepository).save(argThat((AgentRun r) ->
                r.getStatus() == RunStatus.COMPLETED
                && "done".equals(r.getOutput())
                && r.getInputTokens() == null));
    }

    // T004a — Race shape: resume thread successfully finalizes the run as COMPLETED at
    // version V+1 (output="resume-output"). Sweeper thread had loaded the row at version V
    // and tries to write CANCELLED; on its OptimisticLockingFailureException retry, the
    // row reload now sees status=COMPLETED. Without the terminal-state guard, the retry
    // proceeds to overwrite COMPLETED with CANCELLED and clobber the resume's output with
    // the sweeper's reason text. This case asserts the guard prevents the clobber.
    //
    // We simulate the post-retry reload directly: findById returns a row ALREADY in a
    // terminal state, then verify save() is NEVER invoked (guard short-circuits before any
    // setStatus call). The deep-design report's §2.1 race claim is structurally addressed
    // by this single in-process guard; cross-pod ordering remains a PR-7 concern.
    @Test
    void finalizeRun_doesNotClobberAlreadyTerminalRow_evenWhenSweeperRetries() {
        AgentRun terminalReload = new AgentRun();
        terminalReload.setId("run-clobber");
        terminalReload.setStatus(RunStatus.COMPLETED);
        terminalReload.setOutput("resume-output");
        when(runRepository.findByIdForUpdate("run-clobber")).thenReturn(Optional.of(terminalReload));

        finalizer.finalizeRun("run-clobber", RunStatus.CANCELLED,
                "Run cancelled by stuck-PAUSED scheduler", null, null);

        verify(runRepository, never()).save(any(AgentRun.class));
        assertEquals(RunStatus.COMPLETED, terminalReload.getStatus(),
                "row entity must remain COMPLETED — guard short-circuited before any setStatus call");
        assertEquals("resume-output", terminalReload.getOutput(),
                "row's output must be preserved — sweeper's reason text must NOT clobber the resume's output");
    }

    // T004a companion — the inverse race: sweeper finalizes first (CANCELLED at V+1),
    // resume's finalizeRun retries and reloads, sees CANCELLED, must NOT overwrite with
    // COMPLETED. Same guard, different terminal-state pair.
    @Test
    void finalizeRun_doesNotClobberCancelledRow_whenResumeFinalizesLate() {
        AgentRun terminalReload = new AgentRun();
        terminalReload.setId("run-clobber-2");
        terminalReload.setStatus(RunStatus.CANCELLED);
        terminalReload.setOutput("Run cancelled by stuck-PAUSED scheduler");
        when(runRepository.findByIdForUpdate("run-clobber-2")).thenReturn(Optional.of(terminalReload));

        finalizer.finalizeRun("run-clobber-2", RunStatus.COMPLETED,
                "resume-output", null, null);

        verify(runRepository, never()).save(any(AgentRun.class));
        assertEquals(RunStatus.CANCELLED, terminalReload.getStatus(),
                "row entity must remain CANCELLED — resume's late finalize must not resurrect a cancelled run");
    }

    // T004a edge case — terminal-state guard must NOT trigger on PAUSED reload, because
    // PAUSED is a recoverable non-terminal state. Otherwise resume → continueRun's
    // finalizeRun call (which transitions PAUSED → COMPLETED/CANCELLED) would silently
    // skip every write and leave runs stuck PAUSED forever.
    @Test
    void finalizeRun_pausedRowIsNotTreatedAsTerminal_writesProceed() {
        AgentRun pausedReload = new AgentRun();
        pausedReload.setId("run-paused");
        pausedReload.setStatus(RunStatus.PAUSED);
        when(runRepository.findByIdForUpdate("run-paused")).thenReturn(Optional.of(pausedReload));

        finalizer.finalizeRun("run-paused", RunStatus.COMPLETED, "completed-output", null, null);

        ArgumentCaptor<AgentRun> cap = ArgumentCaptor.forClass(AgentRun.class);
        verify(runRepository).save(cap.capture());
        assertEquals(RunStatus.COMPLETED, cap.getValue().getStatus(),
                "PAUSED is recoverable — guard must not trigger; PAUSED → COMPLETED transition must proceed");
    }

    // PR-6 — Lock-exhaust observability: when the 3-attempt retry loop exhausts, the
    // requested terminal write is lost. Today the loss is silent at the application level
    // (WARN log only); this counter gives SREs a queryable signal to alert on. Tag
    // `status` distinguishes which terminal status was lost, so a sustained CANCELLED-loss
    // rate (e.g., sweeper losing to resume) is distinguishable from COMPLETED-loss (resume
    // losing to sweeper) in dashboards. Replaces #359's @Disabled
    // finalizeRun_lockExhaustionIncrementsCounter_pinsObservabilityGap placeholder.
    @Test
    void finalizeRun_lockExhaustionIncrementsCounter() {
        when(runRepository.findByIdForUpdate("run-exhaust")).thenAnswer(inv -> {
            AgentRun fresh = new AgentRun();
            fresh.setId("run-exhaust");
            fresh.setStatus(RunStatus.RUNNING);
            return Optional.of(fresh);
        });
        when(runRepository.save(any(AgentRun.class)))
                .thenThrow(new OptimisticLockingFailureException("v1"))
                .thenThrow(new OptimisticLockingFailureException("v2"))
                .thenThrow(new OptimisticLockingFailureException("v3"));

        finalizer.finalizeRun("run-exhaust", RunStatus.FAILED, null, null, null);

        Counter counter = meterRegistry.find(MetricConstants.HITL_FINALIZE_LOCK_EXHAUSTED_TOTAL)
                .tag("status", "FAILED")
                .counter();
        assertNotNull(counter,
                "lock-exhaust counter must be registered with status=FAILED — without it, SREs cannot detect cross-pod finalize contention");
        assertEquals(1.0, counter.count(),
                "counter must increment exactly once per exhausted finalize call");
    }

    @Test
    void finalizeRun_lockExhaustion_carriesNullStatusAsTagValue() {
        when(runRepository.findByIdForUpdate("run-exhaust-null")).thenAnswer(inv -> {
            AgentRun fresh = new AgentRun();
            fresh.setId("run-exhaust-null");
            fresh.setStatus(RunStatus.RUNNING);
            return Optional.of(fresh);
        });
        when(runRepository.save(any(AgentRun.class)))
                .thenThrow(new OptimisticLockingFailureException("v1"))
                .thenThrow(new OptimisticLockingFailureException("v2"))
                .thenThrow(new OptimisticLockingFailureException("v3"));

        // Pass terminalStatus=null — the existing contract allows nulls, the counter
        // must record "n/a" so it's still SRE-visible without an NPE.
        finalizer.finalizeRun("run-exhaust-null", null, "output", null, null);

        Counter counter = meterRegistry.find(MetricConstants.HITL_FINALIZE_LOCK_EXHAUSTED_TOTAL)
                .tag("status", "n/a")
                .counter();
        assertNotNull(counter,
                "null terminalStatus must surface as tag 'n/a', not throw NPE during counter registration");
        assertEquals(1.0, counter.count());
    }

    @Test
    void finalizeRun_successfulFinalize_doesNotIncrementCounter() {
        AgentRun fresh = new AgentRun();
        fresh.setId("run-clean");
        fresh.setStatus(RunStatus.RUNNING);
        when(runRepository.findByIdForUpdate("run-clean")).thenReturn(Optional.of(fresh));

        finalizer.finalizeRun("run-clean", RunStatus.COMPLETED, "ok", null, null);

        // Counter shouldn't even be registered if no exhaust ever happened — find() returns null.
        assertNull(meterRegistry.find(MetricConstants.HITL_FINALIZE_LOCK_EXHAUSTED_TOTAL).counter(),
                "successful finalize must NOT register the lock-exhaust counter — false-positive metric noise wastes SRE attention");
    }
}
