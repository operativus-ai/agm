package ai.operativus.agentmanager.research;

import ai.operativus.agentmanager.compute.service.AgentRunFinalizer;
import ai.operativus.agentmanager.compute.service.RunExecutionManager;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.registry.RunOperations;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Research test — does NOT pin invariant behavior, used to verify whether the
 * Tier 1.2 audit's F1 finding (cache-key drift on background virtual threads via
 * unbound {@code AgentContextHolder.orgId}) actually reproduces under the codebase's
 * current {@code RunExecutionManager.submit} machinery.
 *
 * <p>Hypothesis: {@code ContextSnapshotFactory.captureAll().wrap(...)} propagates
 * Micrometer's ThreadLocal-backed observation/MDC context, but does NOT propagate
 * Java's JDK 21+ {@code ScopedValue}. {@code Executors.newVirtualThreadPerTaskExecutor().submit(...)}
 * starts a fresh virtual thread without inheriting parent ScopedValue bindings (only
 * StructuredTaskScope inherits, and this code path doesn't use it). Therefore any
 * code reached transitively from the supplier that calls {@code AgentContextHolder.getOrgId()}
 * before an explicit re-bind sees null.
 */
@ExtendWith(MockitoExtension.class)
class F1ScopedValuePropagationVerificationTest {

    @Mock private RunOperations runRepository;
    @Mock private Tracer tracer;
    @Mock private Span span;
    @Mock private AgentRunFinalizer agentRunFinalizer;
    @Test
    void f1_orgIdBoundOnParent_isUnboundOnSpawnedVirtualThread() throws Exception {
        // Tracer/span lenient stubs
        lenient().when(tracer.nextSpan()).thenReturn(span);
        lenient().when(span.name(anyString())).thenReturn(span);
        lenient().when(span.tag(anyString(), anyString())).thenReturn(span);
        lenient().when(span.start()).thenReturn(span);
        // findById returns empty so PR1's safety-net catch path doesn't blow up
        lenient().when(runRepository.findById(anyString())).thenReturn(Optional.empty());

        AtomicReference<String> capturedOrgId = new AtomicReference<>("__UNCAPTURED__");
        AtomicReference<String> capturedRunId = new AtomicReference<>("__UNCAPTURED__");
        AtomicReference<String> capturedUserId = new AtomicReference<>("__UNCAPTURED__");
        AtomicReference<Integer> capturedDepth = new AtomicReference<>(-99);
        CountDownLatch supplierDone = new CountDownLatch(1);

        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", "ORG-A");
        run.setId("research-run-1");

        RunExecutionManager rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer, mock(ai.operativus.agentmanager.control.repository.ApprovalRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(ai.operativus.agentmanager.core.event.AgentRunEventBus.class), 24L);

        // Bind multiple ScopedValues on the controller thread, then submit.
        ScopedValue.where(AgentContextHolder.orgId, "ORG-A")
                .where(AgentContextHolder.userId, "USER-A")
                .where(AgentContextHolder.currentRunId, "PARENT-RUN-1")
                .where(AgentContextHolder.orchestrationDepth, 3)
                .run(() -> {
                    rem.submit(run, () -> {
                        // This runs on the spawned virtual thread.
                        // Capture what AgentContextHolder reports BEFORE any rebind happens
                        // (which simulates what AgentService.run.line:132 sees when it calls
                        // agentRegistry.findById(agentId) BEFORE its own .where(orgId,...).call()).
                        try {
                            capturedOrgId.set(AgentContextHolder.getOrgId());
                            capturedUserId.set(AgentContextHolder.getUserId());
                            capturedRunId.set(AgentContextHolder.getCurrentRunId());
                            capturedDepth.set(AgentContextHolder.getOrchestrationDepth());
                        } finally {
                            supplierDone.countDown();
                        }
                        // Throw a sentinel so RunExecutionManager's catch fires (PR1's safety net)
                        // — but mock setup ensures it's a no-op.
                        throw new RuntimeException("verification-capture-done");
                    });
                });

        // Wait for the VT supplier to run.
        boolean completed = supplierDone.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "Virtual thread supplier did not run within 2s");

        // Report.
        System.out.println("=== F1 ScopedValue propagation verification ===");
        System.out.println("Parent thread bound: orgId=ORG-A, userId=USER-A, currentRunId=PARENT-RUN-1, depth=3");
        System.out.println("Inside spawned VT supplier:");
        System.out.println("  AgentContextHolder.getOrgId()             = " + capturedOrgId.get());
        System.out.println("  AgentContextHolder.getUserId()            = " + capturedUserId.get());
        System.out.println("  AgentContextHolder.getCurrentRunId()      = " + capturedRunId.get());
        System.out.println("  AgentContextHolder.getOrchestrationDepth()= " + capturedDepth.get());
        System.out.println("================================================");

        // Assertion: F1 reproduces if and only if these are NOT propagated.
        // getOrgId returns null when unbound; getUserId falls back to SYSTEM_PRINCIPAL when
        // unbound + no Spring SecurityContext; getCurrentRunId returns null; getOrchestrationDepth
        // returns 0 when unbound.
        boolean orgIdLost = capturedOrgId.get() == null;
        boolean runIdLost = capturedRunId.get() == null;
        boolean depthLost = capturedDepth.get() == 0;

        if (orgIdLost && runIdLost && depthLost) {
            System.out.println("VERDICT: F1 REPRODUCES — captureAll().wrap() does NOT propagate ScopedValue. "
                    + "Any transitive AgentContextHolder.get*() read on the spawned VT sees defaults.");
        } else {
            System.out.println("VERDICT: F1 does NOT reproduce — ScopedValues propagated somehow. Investigate.");
        }

        // Assert the hypothesis (test fails loudly if hypothesis is wrong)
        assertNull(capturedOrgId.get(),
                "F1 verification: AgentContextHolder.orgId on spawned VT should be unbound. "
                        + "If this assertion FAILS, F1 does NOT reproduce — propagation works somehow.");
        assertNull(capturedRunId.get(),
                "F1 verification: AgentContextHolder.currentRunId on spawned VT should be unbound");
        assertEquals(0, capturedDepth.get(),
                "F1 verification: AgentContextHolder.orchestrationDepth should fall back to default 0");
    }

    /**
     * Counter-test: confirms ScopedValues DO work when the canonical pattern is used —
     * i.e. {@code ScopedValue.where(...).call(...)} INSIDE the spawned VT lambda.
     * This is the pattern SwarmOrchestrator/TeamOrchestrationEngine/AgentService.run all
     * follow, and it's what saves them from F1.
     */
    @Test
    void counterTest_canonicalPattern_ScopedValuesAreVisibleInsideRebindScope() throws Exception {
        lenient().when(tracer.nextSpan()).thenReturn(span);
        lenient().when(span.name(anyString())).thenReturn(span);
        lenient().when(span.tag(anyString(), anyString())).thenReturn(span);
        lenient().when(span.start()).thenReturn(span);
        lenient().when(runRepository.findById(anyString())).thenReturn(Optional.empty());

        AtomicReference<String> capturedOrgId = new AtomicReference<>("__UNCAPTURED__");
        CountDownLatch done = new CountDownLatch(1);

        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", "ORG-B");
        run.setId("research-run-2");

        RunExecutionManager rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer, mock(ai.operativus.agentmanager.control.repository.ApprovalRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(ai.operativus.agentmanager.core.event.AgentRunEventBus.class), 24L);

        rem.submit(run, () -> {
            // Mirror the canonical pattern: rebind inside the VT lambda
            return ScopedValue.where(AgentContextHolder.orgId, "ORG-B-REBOUND").call(() -> {
                try {
                    capturedOrgId.set(AgentContextHolder.getOrgId());
                } finally {
                    done.countDown();
                }
                throw new RuntimeException("counter-test-capture-done");
            });
        });

        assertTrue(done.await(2, TimeUnit.SECONDS), "VT did not run");
        System.out.println("Counter-test: rebound inside VT scope = " + capturedOrgId.get());
        assertEquals("ORG-B-REBOUND", capturedOrgId.get(),
                "Canonical pattern: ScopedValue.where(...).call(...) inside the VT lambda must work");
    }
}
