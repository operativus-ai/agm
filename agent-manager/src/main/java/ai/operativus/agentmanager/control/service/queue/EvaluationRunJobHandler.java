package ai.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.entity.EvaluationRun;
import ai.operativus.agentmanager.core.registry.EvaluationOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EvaluationRunJobHandler implements JobHandler {

    public static final String JOB_TYPE = "EVALUATION_RUN";

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunJobHandler.class);

    private final EvaluationOperations evaluationOperations;
    private final ObjectMapper objectMapper;

    public EvaluationRunJobHandler(EvaluationOperations evaluationOperations, ObjectMapper objectMapper) {
        this.evaluationOperations = evaluationOperations;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        log.info("Starting evaluation run for suiteId={} agentId={}", payload.suiteId(), payload.agentId());
        EvaluationRun run = evaluationOperations.runSuite(payload.suiteId(), payload.agentId());
        job.setResult(run.getId());
        log.info("Evaluation run complete: runId={}", run.getId());
    }

    public record Payload(String suiteId, String agentId) {}
}
