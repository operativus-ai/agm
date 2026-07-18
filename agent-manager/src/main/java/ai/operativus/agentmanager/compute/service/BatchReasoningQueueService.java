package ai.operativus.agentmanager.compute.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import io.micrometer.core.annotation.Timed;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Domain Responsibility: Manages non-critical, lower priority inference requests by placing them in an in-memory queue and processing them asynchronously on a fixed schedule (to prevent peak-demand lock conditions).
 * State: Stateful (In-Memory Queue Carrier)
 */
@Service
public class BatchReasoningQueueService {

    private static final Logger log = LoggerFactory.getLogger(BatchReasoningQueueService.class);

    private final ConcurrentLinkedQueue<ReasoningTask> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger processingCount = new AtomicInteger(0);
    private final ai.operativus.agentmanager.core.registry.AgentOperations agentOperations;

    public BatchReasoningQueueService(ai.operativus.agentmanager.core.registry.AgentOperations agentOperations) {
        this.agentOperations = agentOperations;
    }

    public record ReasoningTask(String id, String agentId, String payload) {}

    /**
     * @summary Submits a non-critical reasoning task to the background queue.
     * @logic Enqueues the given task record into a ConcurrentLinkedQueue and logs its acceptance.
     */
    public void enqueueTask(String id, String agentId, String payload) {
        queue.add(new ReasoningTask(id, agentId, payload));
        log.debug("Enqueued batch reasoning task: {} for agent: {}", id, agentId);
    }

    /**
     * @summary Processes queued tasks in bulk according to standard FinOps utilization strategies.
     * @logic Executes every 30 seconds. Dequeues up to a hardcoded limit of tasks (e.g. 5) per cycle, dynamically simulating bulk-processing execution within Virtual Threads.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.batch-poll-ms:30000}")
    @Timed(value = "agent.reasoning.batch.cycle", description = "Time taken for a complete batch processing cycle")
    public void processBatch() {
        if (queue.isEmpty()) {
            return; // No work
        }

        int maxBatchSize = 5;
        int processedThisCycle = 0;

        log.info("Starting Batch Reasoning Queue processor cycle. Queue Size: {}", queue.size());

        while (!queue.isEmpty() && processedThisCycle < maxBatchSize) {
            ReasoningTask task = queue.poll();
            if (task != null) {
                processedThisCycle++;
                processingCount.incrementAndGet();
                
                // Spawn a fire-and-forget Virtual Thread to do the mock inference processing
                Thread.ofVirtual().name("batch-reasoning-" + task.id()).start(() -> {
                    try {
                        log.info("Processing non-critical task ID: {} by Agent: {}", task.id(), task.agentId());
                        agentOperations.run(task.agentId(), task.payload(), java.util.UUID.randomUUID().toString());
                        log.info("Completed non-critical task ID: {}", task.id());
                    } catch (Exception e) {
                        log.error("Batch reasoning task {} failed", task.id(), e);
                    } finally {
                        processingCount.decrementAndGet();
                    }
                });
            }
        }
    }

    /**
     * @summary Retrieves the current queue depth for observability/telemetry.
     * @logic Returns the integer size of the thread-safe Queue.
     */
    public int getQueueDepth() {
        return queue.size();
    }
    
    /**
     * @summary Retrieves the total number of tasks currently actively processing in Virtual Threads.
     */
    public int getActiveProcessingCount() {
        return processingCount.get();
    }
}
