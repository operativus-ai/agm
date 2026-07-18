package ai.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.service.KnowledgeBaseService;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class KnowledgeBaseDeletionJobHandler implements JobHandler {

    public static final String JOB_TYPE = "KNOWLEDGE_BASE_DELETION";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseDeletionJobHandler.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseDeletionJobHandler(KnowledgeBaseService knowledgeBaseService, ObjectMapper objectMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        UUID kbId = UUID.fromString(payload.kbId());
        log.info("Starting knowledge base deletion: kbId={}", kbId);
        knowledgeBaseService.deleteWithCascade(kbId);
        log.info("Knowledge base deletion initiated: kbId={}", kbId);
    }

    public record Payload(String kbId) {}
}
