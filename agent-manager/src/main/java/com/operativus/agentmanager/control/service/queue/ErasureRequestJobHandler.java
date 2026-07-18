package com.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.ErasureOrchestrationService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.entity.ErasureRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ErasureRequestJobHandler implements JobHandler {

    public static final String JOB_TYPE = "ERASURE_REQUEST";

    private static final Logger log = LoggerFactory.getLogger(ErasureRequestJobHandler.class);

    private final ErasureOrchestrationService erasureOrchestrationService;
    private final ObjectMapper objectMapper;

    public ErasureRequestJobHandler(ErasureOrchestrationService erasureOrchestrationService, ObjectMapper objectMapper) {
        this.erasureOrchestrationService = erasureOrchestrationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        log.warn("Processing GDPR erasure request: userId={} requestedBy={} orgId={}",
                payload.userId(), payload.requestedBy(), payload.orgId());
        ErasureRequest result = erasureOrchestrationService.submitAndProcess(
                payload.userId(), payload.requestedBy(), payload.orgId());
        job.setResult(result.getId().toString());
        log.info("Erasure request completed: erasureRequestId={} status={}", result.getId(), result.getStatus());
    }

    /**
     * Bug #50 — orgId is captured at enqueue time on the request thread (where
     * {@code AgentContextHolder.getOrgId()} is bound) and carried in the payload
     * so the worker thread can stamp it on the {@code erasure_requests} row.
     */
    public record Payload(String userId, String requestedBy, String orgId) {
        /** Backwards-compatible constructor — payloads enqueued before the
         *  orgId field was added deserialize with orgId=null. */
        public Payload(String userId, String requestedBy) {
            this(userId, requestedBy, null);
        }
    }
}
