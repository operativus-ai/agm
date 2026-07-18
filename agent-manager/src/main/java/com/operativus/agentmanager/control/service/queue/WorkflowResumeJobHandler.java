package com.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.WorkflowService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkflowResumeJobHandler implements JobHandler {

    public static final String JOB_TYPE = "WORKFLOW_RESUME";

    private static final Logger log = LoggerFactory.getLogger(WorkflowResumeJobHandler.class);

    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public WorkflowResumeJobHandler(WorkflowService workflowService, ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        log.info("Resuming workflow run: runId={}", payload.runId());
        workflowService.resumeWorkflowRun(payload.runId(), payload.output());
        log.info("Workflow run resumed: runId={}", payload.runId());
    }

    public record Payload(String runId, String output) {}
}
