package ai.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeIngestionJobHandler implements JobHandler {

    public static final String JOB_TYPE = "KNOWLEDGE_INGESTION";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionJobHandler.class);

    private final AgentOperations agentOperations;
    private final ObjectMapper objectMapper;

    public KnowledgeIngestionJobHandler(AgentOperations agentOperations, ObjectMapper objectMapper) {
        this.agentOperations = agentOperations;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        log.info("Starting knowledge ingestion for agentId={}", payload.agentId());
        agentOperations.loadKnowledge(payload.agentId());
        log.info("Knowledge ingestion complete for agentId={}", payload.agentId());
    }

    public record Payload(String agentId) {}
}
