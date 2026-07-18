package com.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.WorkflowService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: BackgroundJob handler for REQ-DR-4 HITL route
 *     selection. Delegates to {@link WorkflowService#continueAfterRouteSelection}
 *     to resolve the chosen branch and resume the workflow run from
 *     {@code RunStatus.AWAITING_ROUTE_SELECTION}.
 * State: Stateless (Spring bean)
 */
@Component
public class WorkflowContinueJobHandler implements JobHandler {

    public static final String JOB_TYPE = "WORKFLOW_CONTINUE";

    private static final Logger log = LoggerFactory.getLogger(WorkflowContinueJobHandler.class);

    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public WorkflowContinueJobHandler(WorkflowService workflowService, ObjectMapper objectMapper) {
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
        log.info("Continuing workflow run after route selection: runId={}, choiceKey={}",
                payload.runId(), payload.choiceKey());
        workflowService.continueAfterRouteSelection(payload.runId(), payload.choiceKey());
        log.info("Workflow run continue dispatched: runId={}", payload.runId());
    }

    public record Payload(String runId, String choiceKey) {}
}
