package com.operativus.agentmanager.core.spi;

import java.util.Map;

/**
 * Service Provider Interface (SPI) for defining deterministic Native Java Tools.
 * Framework scans for these implementation beans and dynamically registers them
 * to the generic LLM context natively, avoiding monolithic package binding requirements.
 */
public interface CustomToolExtension {

    /**
     * @return the programmatic name of the tool (must map to typical LLM function call regex matching boundaries).
     */
    String getToolName();

    /**
     * @return Human-readable description informing the LLM when to utilize this tool contextually.
     */
    String getToolDescription();

    /**
     * Defines the JSON-schema mapping properties required to invoke the tool.
     * @return Raw JSON schema representation string.
     */
    String getToolSchema();

    /**
     * Native physical invocation method mapped dynamically to the LLM backend processor.
     * @param arguments Function arguments extracted dynamically via the LLM schema invocation.
     * @return Text representation response strictly yielded backward to the inference execution pipeline.
     */
    String execute(Map<String, Object> arguments);
}
