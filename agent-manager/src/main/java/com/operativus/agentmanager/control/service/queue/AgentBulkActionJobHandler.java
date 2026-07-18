package com.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.AgentBulkOperationsService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentBulkActionJobHandler implements JobHandler {

    public static final String JOB_TYPE = "AGENT_BULK_ACTION";

    private static final Logger log = LoggerFactory.getLogger(AgentBulkActionJobHandler.class);

    private final AgentBulkOperationsService agentBulkOperationsService;
    private final ObjectMapper objectMapper;

    public AgentBulkActionJobHandler(AgentBulkOperationsService agentBulkOperationsService, ObjectMapper objectMapper) {
        this.agentBulkOperationsService = agentBulkOperationsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        log.info("Starting bulk action '{}' on {} agents", payload.action(), payload.ids().size());
        Map<String, Integer> result = switch (payload.action().toUpperCase()) {
            case "ENABLE"  -> agentBulkOperationsService.enableAll(payload.ids());
            case "DISABLE" -> agentBulkOperationsService.disableAll(payload.ids());
            case "DELETE"  -> agentBulkOperationsService.deleteAll(payload.ids());
            default -> throw new IllegalArgumentException("Unknown bulk action: " + payload.action());
        };
        job.setResult(objectMapper.writeValueAsString(result));
        log.info("Bulk action '{}' complete: affected={}", payload.action(), result.get("affected"));
    }

    public record Payload(String action, List<String> ids) {}
}
