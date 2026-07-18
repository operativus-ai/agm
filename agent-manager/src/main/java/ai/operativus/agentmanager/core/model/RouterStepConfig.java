package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.model.enums.RouteSelectorType;

import java.util.Map;

/**
 * Domain Responsibility: Structured configuration carried by a ROUTER
 *     workflow step (REQ-DR-4). Persisted as JSONB under
 *     {@code workflow_steps.router_config} and parsed back via Jackson at
 *     dispatch time.
 *
 *     <ul>
 *       <li>{@code selectorType} — RULE / LLM / HITL; how the choice key is
 *           produced. Required.</li>
 *       <li>{@code selectorExpression} — JSONPath (RULE), classification
 *           prompt (LLM), or null (HITL).</li>
 *       <li>{@code choices} — map of choice key → target {@code stepId}.
 *           The dispatcher branches to the matched step. Required, non-empty.</li>
 *       <li>{@code defaultChoice} — fallback choice key when the selector
 *           returns a value not present in {@code choices}. Optional; null
 *           means "fail the run on no match".</li>
 *     </ul>
 * State: Stateless (Immutable Record carrier)
 */
public record RouterStepConfig(
        RouteSelectorType selectorType,
        String selectorExpression,
        Map<String, String> choices,
        String defaultChoice
) {}
