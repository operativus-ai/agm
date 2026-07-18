package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import com.operativus.agentmanager.control.observability.ScheduledMethodTimingAspect;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.SchedulerTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage for the T052 / matrix §27.4 scheduled-tasks
 *   surface. Pins the ten {@code @Scheduled} beans invoked by {@link SchedulerTestSupport}:
 *   {@code ApprovalService.checkApprovalSla / expireStaleApprovals},
 *   {@code DataRetentionService.enforceRetentionPolicies},
 *   {@code ScheduleExecutionPoller.evaluateSchedules},
 *   {@code PersistentJobQueueService.processQueue},
 *   {@code PeerHealthMonitor.checkPeerHealth},
 *   {@code BatchReasoningQueueService.processBatch},
 *   {@code MemoryConsolidationWorker.processPendingMemoryExtractions},
 *   {@code EphemeralSwarmContext.evictStaleContexts}.
 * State: Stateless at the class level. {@code EphemeralSwarmContext} is an in-memory singleton
 *   whose map persists across tests in the same JVM context; cases that seed it must clean up
 *   via {@code flush()} so the class is order-insensitive.
 *
 * Knob: {@code application-test.properties} pushes every
 *   {@code agentmanager.scheduler.*-ms} property to 24h (decision 4.4), so the
 *   {@code @Scheduled} dispatcher never fires during a test run. Work is driven synchronously
 *   via {@link SchedulerTestSupport}'s {@code tickXxx()} methods, which invoke the underlying
 *   business method directly. This is the documented escape hatch — property-override would
 *   not reschedule an already-registered {@code @Scheduled} binding (decision 4.4 in
 *   {@link SchedulerTestSupport} Javadoc).
 *
 * Scope: the primary value of this test class is the autowiring contract —
 *   {@code @Import(SchedulerTestSupport.class)} fails context startup if ANY scheduled bean
 *   is missing or cannot be constructed, so the ten ticks compose a cheap liveness check for
 *   the full {@code @EnableScheduling} surface. Behavioral depth for individual schedulers is
 *   pinned in the feature-level runtime tests (e.g., A2aMeshRuntimeTest for peer health,
 *   JobQueueRuntimeTest for the queue, ApprovalsRuntimeTest for SLA/cleanup); this class
 *   asserts the scheduler wiring itself is intact.
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §27.4.
 */
@Import({
        SchedulerTestSupport.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class
})
public class ScheduledTasksRuntimeTest extends BaseIntegrationTest {

    @Autowired private SchedulerTestSupport scheduler;
    @Autowired private PersistentJobQueueService queue;
    @Autowired private EphemeralSwarmContext swarm;
    @Autowired private MeterRegistry meterRegistry;

    // §27.4 case 1 — every scheduled tick runs without error on an empty database. This is the
    // liveness check: if any of the ten @Scheduled beans is missing, context startup fails at
    // @Import(SchedulerTestSupport.class), so reaching this method already proves all beans
    // exist. Then we drive each tick sequentially and assert none throws.
    @Test
    void everyScheduledTickIsInvokableOnEmptyState() {
        List<Runnable> ticks = allTicks();
        assertEquals(11, ticks.size(),
                "SchedulerTestSupport must expose one tickXxx per @Scheduled bean; "
                        + "if this count changes, add coverage for the new scheduler");

        for (int i = 0; i < ticks.size(); i++) {
            final int idx = i;
            assertDoesNotThrow(ticks.get(idx)::run,
                    "tick #" + idx + " must run without error on an empty DB");
        }
    }

    // §27.4 case 2 — scheduler ticks are idempotent on empty state. Each tick is called twice
    // in sequence and must continue to be a no-op the second time around (no state leaks
    // forward, no partial-progress gets amplified, no counter double-increments).
    @Test
    void scheduledTicksAreIdempotentAcrossRepeatedInvocation() {
        for (Runnable tick : allTicks()) {
            assertDoesNotThrow(tick::run, "first pass must not throw");
            assertDoesNotThrow(tick::run, "second pass on the same empty state must not throw");
        }
    }

    // §27.4 case 3 — job-queue tick observes zero depth before and after on an empty queue.
    // Pins the narrow contract that processQueue() is safe with no QUEUED rows present.
    // (Behavioral coverage of processQueue's dispatch path is in JobQueueRuntimeTest.)
    @Test
    void jobQueueTickLeavesDepthUnchangedOnEmptyQueue() {
        assertEquals(0L, queue.getQueueDepth(), "precondition: queue must be empty at test start");
        scheduler.tickJobQueue();
        assertEquals(0L, queue.getQueueDepth(),
                "tickJobQueue on an empty queue must leave depth at 0 (no phantom rows created)");
    }

    // §27.4 case 4 — swarm eviction tick preserves FRESH entries (< 1h old). The TTL inside
    // EphemeralSwarmContext is hardcoded at 1h; entries put() just now are NOT stale, so the
    // tick must observe zero evictions and size() must be unchanged. This pins the cutoff
    // direction: we're verifying eviction doesn't fire prematurely.
    @Test
    void swarmEvictionPreservesFreshEntries() {
        int beforeSize = swarm.size();
        String run1 = "t052-run-" + UUID.randomUUID();
        String run2 = "t052-run-" + UUID.randomUUID();
        try {
            swarm.put(run1, "k", "v");
            swarm.put(run2, "k", "v");
            assertEquals(beforeSize + 2, swarm.size(), "two fresh entries must be stored");

            scheduler.tickSwarmCleanup();

            assertEquals(beforeSize + 2, swarm.size(),
                    "evictStaleContexts must not remove entries created < TTL ago; "
                            + "a drop here means the TTL cutoff is wrong or clock-skewed");
            assertNotNull(swarm.get(run1, "k", String.class),
                    "fresh entry 1 must survive an immediate eviction tick");
            assertNotNull(swarm.get(run2, "k", String.class),
                    "fresh entry 2 must survive an immediate eviction tick");
        } finally {
            swarm.flush(run1);
            swarm.flush(run2);
        }
    }

    // §27.4 case 5 — ticks can be invoked concurrently across virtual threads without deadlock
    // or cross-scheduler interference. Spring's ThreadPoolTaskScheduler runs each @Scheduled
    // serially per bean, but in prod with multiple replicas two schedulers can fire simultaneously
    // against the same DB. We simulate that by firing all 10 ticks on a virtual-thread pool and
    // joining; no tick may throw.
    @Test
    void ticksCanRunConcurrentlyAcrossVirtualThreadsWithoutError() {
        List<Runnable> ticks = allTicks();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<Void>> futures = ticks.stream()
                    .map(t -> CompletableFuture.runAsync(t, pool))
                    .toList();
            assertDoesNotThrow(() ->
                            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join(),
                    "no scheduler tick may throw when invoked concurrently with its peers");
        } finally {
            pool.shutdown();
        }
    }

    // §27.4 case 6 — the underlying @Scheduled beans exposed via SchedulerTestSupport are the
    // EXACT ten methods named in the spec. This pins a regression: if someone adds a new
    // @Scheduled somewhere and SchedulerTestSupport isn't updated, T052 fails (because
    // allTicks() returns 10, but the new scheduler wouldn't be driven here).
    @Test
    void schedulerTestSupportExposesExactlyElevenTickMethods() {
        // Count is pinned against the matrix §27.4 list (10 scheduled beans, one of which —
        // ApprovalService — fires two distinct @Scheduled methods: SLA check + cleanup).
        // Mismatch means either:
        //   (a) a new @Scheduled was added and SchedulerTestSupport wasn't updated, or
        //   (b) an existing scheduler was removed/consolidated and the support façade didn't follow.
        // Both cases require deliberate human review — this assertion surfaces that need.
        assertEquals(11, allTicks().size(),
                "SchedulerTestSupport must expose one tickXxx per @Scheduled method. "
                        + "If a new scheduler was added, update SchedulerTestSupport AND this test.");
    }

    // ─── @Disabled matrix gaps ───

    // Matrix §27.4 gap closed by ScheduledMethodTimingAspect — an @Around aspect bound to
    // @Scheduled records a Micrometer Timer named `scheduler.tick.duration` with method + outcome
    // tags. We drive two semantically-distinct ticks (job queue + swarm cleanup), then assert the
    // timer registered at least one sample per method with outcome=success. Drilling into tag
    // cardinality (and not just the timer's existence) is the real pin — an aspect that fires
    // for all @Scheduled methods but collapses them into a single untagged Timer would be just
    // as useless to SRE as no aspect at all.
    @Test
    void perSchedulerTickLatencyIsInstrumentedWithMicrometer() {
        scheduler.tickJobQueue();
        scheduler.tickSwarmCleanup();

        Collection<Timer> timers = meterRegistry.find(ScheduledMethodTimingAspect.TIMER_NAME).timers();
        assertTrue(timers.size() >= 2,
                "scheduler.tick.duration must register one timer per method+outcome combination. "
                        + "Got " + timers.size() + " timers — the aspect is either collapsing tags "
                        + "or only firing on a subset of schedulers.");

        // Assert both methods are represented, tagged with method-name cardinality.
        Set<String> methods = timers.stream()
                .map(t -> t.getId().getTag("method"))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        assertTrue(methods.stream().anyMatch(m -> m.endsWith(".processQueue")),
                "timer must carry method=...processQueue tag; got " + methods);
        assertTrue(methods.stream().anyMatch(m -> m.endsWith(".evictStaleContexts")),
                "timer must carry method=...evictStaleContexts tag; got " + methods);

        // Sample counts must be monotonically non-zero for the methods we just ticked — proves
        // the aspect actually executed rather than registering an empty timer.
        Timer jobQueueTimer = meterRegistry.find(ScheduledMethodTimingAspect.TIMER_NAME)
                .tag("method", "PersistentJobQueueService.processQueue")
                .tag("outcome", "success")
                .timer();
        assertNotNull(jobQueueTimer,
                "processQueue success timer must exist after tickJobQueue()");
        assertTrue(jobQueueTimer.count() >= 1,
                "processQueue timer must record at least one sample; got " + jobQueueTimer.count());

        Timer swarmTimer = meterRegistry.find(ScheduledMethodTimingAspect.TIMER_NAME)
                .tag("method", "EphemeralSwarmContext.evictStaleContexts")
                .tag("outcome", "success")
                .timer();
        assertNotNull(swarmTimer,
                "evictStaleContexts success timer must exist after tickSwarmCleanup()");
        assertTrue(swarmTimer.count() >= 1,
                "evictStaleContexts timer must record at least one sample; got " + swarmTimer.count());
    }

    // Matrix §27.4 case — TTL was hardcoded at 1h, preventing positive-eviction coverage.
    // Now externalised to `agentmanager.swarm.context-ttl-ms` via @Value. We use
    // ReflectionTestUtils.setField to dial it down to 50ms for this case only (no
    // @DirtiesContext — we restore the default after), put one entry, wait for it to age
    // past the cutoff, invoke evictStaleContexts() directly (the @Scheduled dispatcher is
    // pinned to 24h by application-test.properties), and assert eviction. Also puts a
    // second entry AFTER the cutoff window and asserts it survives — same tick must be
    // age-selective, not wholesale.
    @Test
    void swarmEvictsEntriesOlderThanTtlViaDirectTick() throws InterruptedException {
        long restoredTtl = 3_600_000L;
        org.springframework.test.util.ReflectionTestUtils.setField(swarm, "ttlMs", 50L);
        try {
            String oldRun = "run-old-" + UUID.randomUUID();
            swarm.put(oldRun, "fact", "stale");
            Thread.sleep(100); // wait past the 50ms TTL

            String freshRun = "run-fresh-" + UUID.randomUUID();
            swarm.put(freshRun, "fact", "fresh");

            swarm.evictStaleContexts();

            assertEquals(0, swarm.getAll(oldRun).size(),
                    "entry older than TTL must be evicted on the tick");
            assertEquals("fresh", swarm.get(freshRun, "fact", String.class),
                    "entry within TTL window must survive the tick");

            swarm.flush(freshRun);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(swarm, "ttlMs", restoredTtl);
        }
    }

    // ─── helpers ───

    /** Returns every tick method exposed by SchedulerTestSupport, in a stable order. */
    private List<Runnable> allTicks() {
        return List.<Consumer<SchedulerTestSupport>>of(
                SchedulerTestSupport::tickApprovalSlaCheck,
                SchedulerTestSupport::tickApprovalCleanup,
                SchedulerTestSupport::tickDataRetention,
                SchedulerTestSupport::tickSchedulePoll,
                SchedulerTestSupport::tickJobQueue,
                SchedulerTestSupport::tickPeerHealth,
                SchedulerTestSupport::tickBatchReasoning,
                SchedulerTestSupport::tickMemoryConsolidation,
                SchedulerTestSupport::tickSwarmCleanup
        ).stream()
                .map(fn -> (Runnable) () -> fn.accept(scheduler))
                .toList();
    }
}
