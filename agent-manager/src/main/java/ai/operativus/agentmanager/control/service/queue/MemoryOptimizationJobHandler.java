package ai.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.registry.MemoryOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MemoryOptimizationJobHandler implements JobHandler {

    public static final String JOB_TYPE = "MEMORY_OPTIMIZATION";

    private static final Logger log = LoggerFactory.getLogger(MemoryOptimizationJobHandler.class);

    private final MemoryOperations memoryOperations;
    private final ObjectMapper objectMapper;

    public MemoryOptimizationJobHandler(MemoryOperations memoryOperations, ObjectMapper objectMapper) {
        this.memoryOperations = memoryOperations;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        log.info("Starting memory optimization for userId={}", payload.userId());
        memoryOperations.optimizeMemories(payload.userId());
        log.info("Memory optimization complete for userId={}", payload.userId());
    }

    public record Payload(String userId) {}
}
