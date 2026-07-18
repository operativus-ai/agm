package com.operativus.agentmanager.core.spi;

import java.util.Map;

/**
 * Service Provider Interface (SPI) enabling external execution topologies inside workflows.
 * Instead of routing a WorkflowStep to an LLM `Agent`, it routes the execution control 
 * purely to this interface (allowing deterministic calculations, REST callbacks, or native logic).
 */
public interface WorkflowStepExecutorExtension {

    /**
     * Determines if this extension is responsible for executing the requested agentId or action context.
     * @param executorId Defines the target executor mapping string.
     * @return true if this component can handle the execution.
     */
    boolean supports(String executorId);

    /**
     * Executes the custom logic synchronously.
     *
     * @param executorId The same string that successfully passed the `supports()` check.
     * @param workflowId The parent Workflow definition ID.
     * @param runId Tracking ID of the specific execution run.
     * @param inputPayload Data piped in from the preceding workflow step.
     * @param context Immutable map containing tracking metadata.
     * @return Transformed output to pass to the next node in the playlist.
     */
    String executeStep(String executorId, String workflowId, String runId, String inputPayload, Map<String, Object> context);
}
