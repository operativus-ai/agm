package com.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.AgentBulkOperationsService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BulkExportJobHandler implements JobHandler {

    public static final String JOB_TYPE = "BULK_EXPORT";

    private static final Logger log = LoggerFactory.getLogger(BulkExportJobHandler.class);

    private final AgentBulkOperationsService agentBulkOperationsService;
    private final ObjectMapper objectMapper;

    public BulkExportJobHandler(AgentBulkOperationsService agentBulkOperationsService, ObjectMapper objectMapper) {
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
        log.info("Starting bulk export for {} agents", payload.ids().size());
        List<AgentDefinition> definitions = agentBulkOperationsService.exportAll(payload.ids());
        job.setResult(objectMapper.writeValueAsString(definitions));
        log.info("Bulk export complete: exported={}", definitions.size());
    }

    public record Payload(List<String> ids) {}
}
