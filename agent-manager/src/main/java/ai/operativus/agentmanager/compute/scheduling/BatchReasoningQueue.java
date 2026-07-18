package ai.operativus.agentmanager.compute.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import jakarta.annotation.PreDestroy;

/**
 * Domain Responsibility: Handles FinOps-compliant background delegation of long-running or 
 * low-priority tasks to cheaper "Spot Models".
 * State: Stateful (Manages a local queue and dispatcher thread pool bounded by Virtual Threads).
 */
@Service
public class BatchReasoningQueue {

    private static final Logger log = LoggerFactory.getLogger(BatchReasoningQueue.class);
    
    // Limits the queue depth to avoid memory exhaustion
    private final BlockingQueue<BatchTask> taskQueue = new LinkedBlockingQueue<>(1000);
    
    // Virtual thread pool for dispatching bounded blocking tasks without reactive penalty
    private final ExecutorService dispatcherPool = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("batch-reasoning-", 0).factory()
    );

    public BatchReasoningQueue() {
        startDispatcherLoop();
    }

    public record BatchTask(
            String taskId,
            String teamId,
            String prompt,
            String targetSpotModel
    ) {}

    /**
     * Submits a low priority background reasoning task.
     */
    public boolean submitTask(BatchTask task) {
        if (taskQueue.offer(task)) {
            log.info("Batch task submitted successfully: {}", task.taskId());
            return true;
        } else {
            log.warn("Batch queue is at maximum capacity. Cannot accept task: {}", task.taskId());
            return false;
        }
    }

    private void startDispatcherLoop() {
        dispatcherPool.submit(() -> {
            log.info("BatchReasoningQueue Dispatcher Loop started.");
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    BatchTask task = taskQueue.take(); // Blocks efficiently on Virtual Thread
                    processTask(task);
                }
            } catch (InterruptedException e) {
                log.info("BatchReasoningQueue Dispatcher Loop interrupted.");
                Thread.currentThread().interrupt();
            }
        });
    }

    private void processTask(BatchTask task) {
        log.debug("Processing batch task {} using spot model {}", task.taskId(), task.targetSpotModel());
        
        // Simulating invocation of a cheaper spring-ai ChatClient model
        try {
            Thread.sleep(500); // simulate LLM delay
            log.info("Batch task {} completed successfully.", task.taskId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down BatchReasoningQueue...");
        dispatcherPool.shutdownNow();
    }
}
