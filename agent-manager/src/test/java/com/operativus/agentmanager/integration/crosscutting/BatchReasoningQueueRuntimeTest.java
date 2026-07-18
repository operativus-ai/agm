package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.compute.service.BatchReasoningQueueService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.RecordingAgentOperations;
import com.operativus.agentmanager.integration.support.RecordingAgentOperationsConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Behavior pin for {@link BatchReasoningQueueService#processBatch()} — the
 *   {@code @Scheduled} method previously covered only by the no-throw smoke pin in
 *   {@link ScheduledTasksRuntimeTest}. Locks four invariants of the drain cycle:
 *   <ol>
 *     <li>Empty queue → no work dispatched ({@link RecordingAgentOperations#calls} stays empty)</li>
 *     <li>Below-batch-cap input → all tasks drained in one cycle</li>
 *     <li>Above-batch-cap input → exactly {@code maxBatchSize=5} drained, remainder stays queued</li>
 *     <li>Dispatched tasks carry the enqueued {@code agentId} + {@code payload} into
 *         {@link com.operativus.agentmanager.core.registry.AgentOperations#run}</li>
 *   </ol>
 * State: Stateless. {@link RecordingAgentOperations#reset()} runs in {@code @BeforeEach} so
 *   queued scripts and recorded calls do not leak between cases. Queue is in-memory on the
 *   {@link BatchReasoningQueueService} bean — drained at the start of each test by calling
 *   {@code processBatch()} until {@code getQueueDepth()==0} (defensive against context leak).
 *
 * <p>Why a {@link RecordingAgentOperations} substitution: the real {@code AgentService.run}
 *   would attempt to load a non-existent agent from the empty DB and throw inside the virtual
 *   thread. The exception is swallowed by {@code processBatch}'s catch block, so queue-depth
 *   assertions would still pass — but we would lose the ability to assert WHICH args were
 *   propagated into {@code AgentOperations.run}. The recording stub records every call's
 *   {@code (agentId, input, sessionId)} synchronously.
 *
 * <p>Why Awaitility on agent-call assertions: {@code processBatch} drains the queue
 *   synchronously (the {@link java.util.concurrent.ConcurrentLinkedQueue#poll} runs on the
 *   caller thread), but the {@code agentOperations.run} invocation is dispatched onto a
 *   fire-and-forget virtual thread. Queue-depth assertions are deterministic immediately;
 *   call-recording assertions need a brief poll for the VT to land its append on the
 *   {@link java.util.concurrent.CopyOnWriteArrayList}.
 */
@Import({
        RecordingAgentOperationsConfig.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class
})
public class BatchReasoningQueueRuntimeTest extends BaseIntegrationTest {

    private static final int MAX_BATCH_SIZE = 5;

    @Autowired private BatchReasoningQueueService batchQueue;
    @Autowired private RecordingAgentOperations recordingAgentOperations;

    @BeforeEach
    void clearQueueAndRecordings() {
        // Defensive drain: a prior test's processBatch() might have left items queued if it
        // crashed before assertions completed. Spin processBatch() until the queue is empty.
        while (batchQueue.getQueueDepth() > 0) {
            batchQueue.processBatch();
        }
        // Wait for fire-and-forget VTs spawned by the prior test (or by the drain above) to
        // finish their AgentOperations.run dispatch — otherwise their late append to the
        // RecordingAgentOperations.calls list would race with reset() and leak a phantom
        // call into the next test.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> batchQueue.getActiveProcessingCount() == 0);
        recordingAgentOperations.reset();
    }

    // §27.4 behavior — processBatch on an empty queue is a hard no-op: no virtual threads
    // dispatched, no AgentOperations.run invocations. This is stricter than the smoke pin
    // (no-throw) because it asserts the side-effect surface, not just the absence of error.
    @Test
    void processBatchOnEmptyQueueDoesNotInvokeAgentOperations() {
        assertEquals(0, batchQueue.getQueueDepth(), "precondition: queue must be empty");

        batchQueue.processBatch();

        // Brief poll: even though we expect zero calls, give any phantom VT a window to surface
        // so the assertion fails loudly (rather than racily passing).
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(0, recordingAgentOperations.calls.size(),
                "processBatch on empty queue must not dispatch any AgentOperations.run; "
                        + "got " + recordingAgentOperations.calls.size() + " recorded calls");
    }

    // enqueueTask is the only public surface that grows the queue. Pins the depth invariant
    // so a regression to a different queue (or a silent enqueue swallow) surfaces immediately.
    @Test
    void enqueueTaskIncrementsQueueDepth() {
        assertEquals(0, batchQueue.getQueueDepth());

        batchQueue.enqueueTask("t-1", "agent-a", "payload-1");
        assertEquals(1, batchQueue.getQueueDepth(),
                "single enqueue must raise queueDepth to 1");

        batchQueue.enqueueTask("t-2", "agent-a", "payload-2");
        batchQueue.enqueueTask("t-3", "agent-b", "payload-3");
        assertEquals(3, batchQueue.getQueueDepth(),
                "subsequent enqueues must accumulate");
    }

    // §27.4 behavior — when fewer than maxBatchSize tasks are queued, processBatch drains
    // every one in a single cycle. This pins the drain-loop upper bound (≤ maxBatchSize) but
    // also that it does NOT under-drain (no off-by-one early break).
    @Test
    void processBatchDrainsAllTasksWhenSizeIsBelowBatchCap() {
        for (int i = 0; i < 3; i++) {
            batchQueue.enqueueTask("under-" + i, "agent-x", "p-" + i);
        }
        assertEquals(3, batchQueue.getQueueDepth());

        batchQueue.processBatch();

        assertEquals(0, batchQueue.getQueueDepth(),
                "below-cap queue must be fully drained in one cycle; "
                        + "remaining depth indicates a premature break in the drain loop");
    }

    // §27.4 behavior — the {@code maxBatchSize=5} ceiling is enforced per-cycle. Enqueue 7,
    // call processBatch once, assert depth=2. This is the central pin for the back-pressure
    // contract: if someone bumps the constant without updating the per-cycle cap, this fails.
    @Test
    void processBatchDrainsExactlyFiveTasksPerCycleAboveCap() {
        for (int i = 0; i < 7; i++) {
            batchQueue.enqueueTask("over-" + i, "agent-y", "payload-" + i);
        }
        assertEquals(7, batchQueue.getQueueDepth());

        batchQueue.processBatch();

        assertEquals(2, batchQueue.getQueueDepth(),
                "processBatch must drain exactly " + MAX_BATCH_SIZE + " tasks per cycle; "
                        + "7 enqueued − " + MAX_BATCH_SIZE + " drained = 2 remaining");
    }

    // Multiple cycles eventually drain a queue larger than the batch cap. Pins the
    // resumption contract — processBatch must not skip remaining items, get stuck, or
    // re-drain already-polled tasks.
    @Test
    void multipleProcessBatchCyclesEventuallyDrainAllTasks() {
        for (int i = 0; i < 12; i++) {
            batchQueue.enqueueTask("cycle-" + i, "agent-z", "p-" + i);
        }
        assertEquals(12, batchQueue.getQueueDepth());

        batchQueue.processBatch(); // 12 → 7
        assertEquals(7, batchQueue.getQueueDepth());

        batchQueue.processBatch(); // 7 → 2
        assertEquals(2, batchQueue.getQueueDepth());

        batchQueue.processBatch(); // 2 → 0 (below cap, fully drained)
        assertEquals(0, batchQueue.getQueueDepth(),
                "three cycles on a 12-task queue must drain it completely; "
                        + "remaining depth indicates a poll-pointer leak");
    }

    // §27.4 behavior — the args enqueued into the queue are the args dispatched to
    // AgentOperations.run. Pins the propagation contract: agentId + payload survive the
    // virtual-thread hand-off intact. (sessionId is a fresh UUID per dispatch by design —
    // see BatchReasoningQueueService:66 — so we do not pin its value, only that it is
    // non-blank and unique per task.)
    @Test
    void dispatchedTasksCarryEnqueuedAgentIdAndPayloadToAgentOperations() {
        batchQueue.enqueueTask("prop-1", "agent-alpha", "the-payload-alpha");
        batchQueue.enqueueTask("prop-2", "agent-beta", "the-payload-beta");

        batchQueue.processBatch();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertEquals(2, recordingAgentOperations.calls.size(),
                        "both enqueued tasks must reach AgentOperations.run; "
                                + "got " + recordingAgentOperations.calls.size() + " calls"));

        Set<String> agentIds = recordingAgentOperations.calls.stream()
                .map(RecordingAgentOperations.Call::agentId)
                .collect(Collectors.toSet());
        Set<String> payloads = recordingAgentOperations.calls.stream()
                .map(RecordingAgentOperations.Call::input)
                .collect(Collectors.toSet());
        Set<String> sessionIds = recordingAgentOperations.calls.stream()
                .map(RecordingAgentOperations.Call::sessionId)
                .collect(Collectors.toSet());

        assertEquals(Set.of("agent-alpha", "agent-beta"), agentIds,
                "agentId must propagate from enqueueTask → AgentOperations.run");
        assertEquals(Set.of("the-payload-alpha", "the-payload-beta"), payloads,
                "payload must propagate from enqueueTask → AgentOperations.run as the userInput");
        assertEquals(2, sessionIds.size(),
                "each dispatch must mint a unique sessionId (UUID.randomUUID per task); "
                        + "got " + sessionIds.size() + " distinct ids for 2 dispatches");
        for (String sid : sessionIds) {
            assertTrue(sid != null && !sid.isBlank(),
                    "dispatched sessionId must be non-blank; got '" + sid + "'");
        }
    }

    // §27.4 behavior — when a cycle's remainder rolls into the next cycle, the dispatched
    // tasks across both cycles must collectively cover the full enqueued set. This pins the
    // claim that processBatch does not re-dispatch already-polled tasks (the queue.poll()
    // semantics rather than peek+remove). 7 enqueued + 2 cycles → exactly 7 dispatched, no
    // duplicates.
    @Test
    void multipleCyclesDispatchEachEnqueuedTaskExactlyOnce() {
        IntStream.range(0, 7).forEach(i ->
                batchQueue.enqueueTask("unique-" + i, "agent-q", "payload-" + i));

        batchQueue.processBatch();
        batchQueue.processBatch();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertEquals(7, recordingAgentOperations.calls.size(),
                        "all 7 tasks must be dispatched across the two cycles; got "
                                + recordingAgentOperations.calls.size()));

        Set<String> distinctPayloads = recordingAgentOperations.calls.stream()
                .map(RecordingAgentOperations.Call::input)
                .collect(Collectors.toSet());
        assertEquals(7, distinctPayloads.size(),
                "each enqueued payload must be dispatched exactly once across cycles; "
                        + "duplicates indicate the drain loop re-reads already-polled tasks");
    }
}
