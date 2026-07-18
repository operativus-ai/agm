package com.operativus.agentmanager.compute.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Domain Responsibility: Structured response from {@link LlmAgentClassifier}'s ChatClient
 *     call. The LLM is instructed to return JSON in this shape. {@code confidence} is on
 *     [0.0, 1.0]; below the configured floor (default 0.6) the decision is rejected and
 *     the resolver cascade falls through to the next strategy.
 * State: Stateless (record)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassifierDecision(
        String agentId,
        Double confidence,
        String rationale
) {
}
