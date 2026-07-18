package ai.operativus.agentmanager.core.spi;

import java.util.Map;

/**
 * Service Provider Interface (SPI) for defining custom execution hooks that integrate directly 
 * into the Procurator pipeline. These plugins are discovered dynamically via ServiceLoader context.
 */
public interface AgentHookExtension {

    /**
     * Unique identifier for this specific hook extension.
     * Often formatted similarly to an Agent ID or Component key (e.g. "pii-scrubber-hook").
     */
    String getExtensionId();

    /**
     * Dispatched synchronously BEFORE an LLM payload is sent across the network.
     * Useful for PII masking, token reduction, or deterministic prompt enrichment.
     *
     * @param runId The system UUID generated for the workflow execution.
     * @param rawInput The user's or previous step's unmodified prompt input.
     * @param context Immutable map containing workflow details (model, agent configuration).
     * @return The modified input payload to feed to the LLM. Must never return null.
     */
    String beforeExecution(String runId, String rawInput, Map<String, Object> context);

    /**
     * Dispatched synchronously AFTER an LLM inference payload is received.
     * Useful for deterministic validation checks, semantic filtering, or external database syncing.
     *
     * @param runId The system UUID tracking this execution step.
     * @param llmOutput The unscrubbed completion response from the language model.
     * @param context Immutable map containing tracking metadata.
     * @return The finalized output payload.
     */
    String afterExecution(String runId, String llmOutput, Map<String, Object> context);
}
