package com.operativus.agentmanager.compute.config;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the F23 fix contract — {@link ScopedValueTaskDecorator} captures the caller thread's
 * {@link AgentContextHolder} ScopedValues and rebinds them on a fresh virtual-thread worker.
 * Without the decorator, a fresh VT does NOT inherit ScopedValue bindings; this test would
 * report null/0/default for every captured value.
 */
class ScopedValueTaskDecoratorTest {

    @Test
    void boundScopedValuesOnCallerArePropagatedToVirtualThreadWorker() throws Exception {
        ScopedValueTaskDecorator decorator = new ScopedValueTaskDecorator();

        AtomicReference<String> capturedOrg = new AtomicReference<>("__UNSET__");
        AtomicReference<String> capturedRun = new AtomicReference<>("__UNSET__");
        AtomicReference<Integer> capturedDepth = new AtomicReference<>(-99);
        AtomicReference<RunTelemetryAccumulator> capturedTelemetry = new AtomicReference<>();

        Runnable inWorker = () -> {
            capturedOrg.set(AgentContextHolder.getOrgId());
            capturedRun.set(AgentContextHolder.getCurrentRunId());
            capturedDepth.set(AgentContextHolder.getOrchestrationDepth());
            capturedTelemetry.set(AgentContextHolder.getTelemetry());
        };

        RunTelemetryAccumulator telemetry = new RunTelemetryAccumulator();
        CountDownLatch done = new CountDownLatch(1);

        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            // Bind on the caller, then submit through the decorator. Inside the worker the
            // decorator should rebind so AgentContextHolder.get*() returns the same values.
            ScopedValue.where(AgentContextHolder.orgId, "ORG-A")
                    .where(AgentContextHolder.currentRunId, "RUN-1")
                    .where(AgentContextHolder.orchestrationDepth, 4)
                    .where(AgentContextHolder.telemetry, telemetry)
                    .run(() -> {
                        Runnable wrapped = decorator.decorate(() -> {
                            inWorker.run();
                            done.countDown();
                        });
                        vt.submit(wrapped);
                    });

            assertTrue(done.await(2, TimeUnit.SECONDS), "decorated runnable did not execute");
        }

        assertEquals("ORG-A", capturedOrg.get(), "orgId must propagate to the VT worker");
        assertEquals("RUN-1", capturedRun.get(), "currentRunId must propagate to the VT worker");
        assertEquals(4, capturedDepth.get(), "orchestrationDepth must propagate to the VT worker");
        assertSame(telemetry, capturedTelemetry.get(),
                "telemetry accumulator instance must propagate (not a copy) so async work appends to the parent run's metrics");
    }

    @Test
    void unboundCallerThreadProducesUnboundWorker() throws Exception {
        ScopedValueTaskDecorator decorator = new ScopedValueTaskDecorator();
        AtomicReference<String> capturedOrg = new AtomicReference<>("__UNSET__");
        AtomicReference<Integer> capturedDepth = new AtomicReference<>(-99);
        CountDownLatch done = new CountDownLatch(1);

        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            // No ScopedValue.where on the caller — capture path should observe nothing bound,
            // skip every where(), and run the worker exactly as if no decorator were applied.
            Runnable wrapped = decorator.decorate(() -> {
                capturedOrg.set(AgentContextHolder.getOrgId());
                capturedDepth.set(AgentContextHolder.getOrchestrationDepth());
                done.countDown();
            });
            vt.submit(wrapped);
            assertTrue(done.await(2, TimeUnit.SECONDS));
        }

        assertNull(capturedOrg.get(), "with no caller binding, worker getOrgId() must be null");
        assertEquals(0, capturedDepth.get(), "with no caller binding, getOrchestrationDepth() falls back to 0");
    }
}
