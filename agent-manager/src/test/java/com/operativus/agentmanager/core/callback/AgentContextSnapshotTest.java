package com.operativus.agentmanager.core.callback;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link AgentContextSnapshot} contract — capture on caller, rebind on worker.
 * Without rebind, fresh virtual threads do NOT inherit JDK 21 ScopedValues from the parent;
 * the assertions in {@link #snapshotPropagatesBindingsToVirtualThreadWorker()} would fail.
 */
class AgentContextSnapshotTest {

    @Test
    void snapshotPropagatesBindingsToVirtualThreadWorker() throws Exception {
        AtomicReference<String> capturedOrg = new AtomicReference<>("__UNSET__");
        AtomicReference<String> capturedRun = new AtomicReference<>("__UNSET__");
        AtomicReference<Integer> capturedDepth = new AtomicReference<>(-99);
        CountDownLatch done = new CountDownLatch(1);

        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            ScopedValue.where(AgentContextHolder.orgId, "ORG-A")
                    .where(AgentContextHolder.currentRunId, "RUN-1")
                    .where(AgentContextHolder.orchestrationDepth, 4)
                    .run(() -> {
                        AgentContextSnapshot snapshot = AgentContextSnapshot.capture();
                        vt.submit(() -> snapshot.run(() -> {
                            capturedOrg.set(AgentContextHolder.getOrgId());
                            capturedRun.set(AgentContextHolder.getCurrentRunId());
                            capturedDepth.set(AgentContextHolder.getOrchestrationDepth());
                            done.countDown();
                        }));
                    });

            assertTrue(done.await(2, TimeUnit.SECONDS));
        }

        assertEquals("ORG-A", capturedOrg.get(), "orgId must propagate to the VT worker");
        assertEquals("RUN-1", capturedRun.get(), "currentRunId must propagate to the VT worker");
        assertEquals(4, capturedDepth.get(), "orchestrationDepth must propagate to the VT worker");
    }

    @Test
    void unboundCallerProducesUnboundWorker() throws Exception {
        AtomicReference<String> capturedOrg = new AtomicReference<>("__UNSET__");
        AtomicReference<Boolean> orchestrationDepthBound = new AtomicReference<>(true);
        CountDownLatch done = new CountDownLatch(1);

        AgentContextSnapshot snapshot = AgentContextSnapshot.capture();

        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            vt.submit(() -> snapshot.run(() -> {
                capturedOrg.set(AgentContextHolder.getOrgId());
                // isBound() must remain false for unbound captures — sentinel-binding would
                // make it true and silently break callers that distinguish bound vs unbound.
                orchestrationDepthBound.set(AgentContextHolder.orchestrationDepth.isBound());
                done.countDown();
            }));
            assertTrue(done.await(2, TimeUnit.SECONDS));
        }

        assertNull(capturedOrg.get(), "with no caller binding, getOrgId() returns null");
        assertFalse(orchestrationDepthBound.get(),
                "captured-from-unbound must NOT sentinel-bind the worker — isBound() must stay false");
    }

    @Test
    void callPropagatesReturnValueAndUnwrapsCheckedExceptions() {
        AgentContextSnapshot snapshot = AgentContextSnapshot.capture();

        // Return value passes through.
        Integer result = snapshot.call(() -> 42);
        assertEquals(42, result);

        // Checked exception → RuntimeException unwrap (so Reactor lambdas can use it without
        // having to declare throws clauses they don't have).
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> snapshot.call(() -> { throw new java.io.IOException("boom"); }));
        assertInstanceOf(java.io.IOException.class, ex.getCause());

        // RuntimeException passes through unwrapped (no double-wrapping).
        IllegalStateException ise = assertThrows(IllegalStateException.class,
                () -> snapshot.call(() -> { throw new IllegalStateException("nope"); }));
        assertEquals("nope", ise.getMessage());
    }
}
