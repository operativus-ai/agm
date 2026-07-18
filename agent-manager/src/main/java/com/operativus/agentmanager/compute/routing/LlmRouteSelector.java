package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import com.operativus.agentmanager.core.spi.RouteSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Domain Responsibility: LLM-mode {@link RouteSelector} for REQ-DR-4 ROUTER
 *     steps. Sends a classification prompt to the configured primary LLM
 *     (resolved via {@code ChatConfig.chatClientBuilder}) asking it to pick
 *     ONE of the declared {@code choices} keys based on the prior step's
 *     output. The selector's {@code selectorExpression} is treated as a
 *     user-authored classification instruction prepended to a strict output
 *     guard.
 *
 *     <p>The returned choice key is normalized (trim + lowercase compare) and
 *     validated against {@code config.choices()}. Unknown / malformed
 *     responses fall through to {@code config.defaultChoice()} — never throws
 *     mid-dispatch. The dispatcher fails the run when both the LLM and the
 *     default produce no usable key.
 *
 *     <p>This selector does NOT wire the AGM advisor chain (no PII redaction,
 *     no RAG, no metrics). The prior step's output is the only payload that
 *     reaches the model; AGM's advisors only fire on per-agent runs.
 *
 * State: Stateful (caches a built ChatClient at construction)
 */
@Component
public class LlmRouteSelector implements RouteSelector {

    private static final Logger log = LoggerFactory.getLogger(LlmRouteSelector.class);

    private final ChatClient chatClient;

    public LlmRouteSelector(ChatClient.Builder chatClientBuilder) {
        // No advisors — single-shot classification. Per-org PII / safety
        // posture for routing decisions is a separate product call deferred
        // until LLM selectors land in real workflows.
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public RouteSelectorType selectorType() {
        return RouteSelectorType.LLM;
    }

    @Override
    public String selectChoice(RouterStepConfig config, String priorStepOutput) {
        if (config == null) {
            throw new IllegalArgumentException("LlmRouteSelector requires non-null RouterStepConfig");
        }
        String prompt = buildPrompt(config, priorStepOutput);
        String raw;
        try {
            raw = chatClient.prompt(prompt).call().content();
        } catch (RuntimeException e) {
            log.warn("LLM selector ChatClient call failed ({}); using defaultChoice='{}'",
                    e.getClass().getSimpleName(), config.defaultChoice());
            return config.defaultChoice();
        }
        return resolveChoice(raw, config);
    }

    /**
     * Package-private: builds the classification prompt. Composed of
     * the operator-authored {@code selectorExpression} (or a default
     * instruction when null/blank), the choice key list, and the
     * prior step's output. Strict output guard at the bottom keeps
     * the model from yapping.
     */
    static String buildPrompt(RouterStepConfig config, String priorStepOutput) {
        String instruction = (config.selectorExpression() == null || config.selectorExpression().isBlank())
                ? "Classify the prior output into ONE of the categories below."
                : config.selectorExpression();
        Set<String> keys = config.choices() == null ? Set.of() : config.choices().keySet();
        return instruction
                + "\n\nCategories: " + String.join(", ", keys)
                + "\n\nPrior output:\n" + (priorStepOutput == null ? "" : priorStepOutput)
                + "\n\nRespond with EXACTLY one category name from the list above. "
                + "No explanation, no punctuation, no quotes — just the category.";
    }

    /**
     * Package-private: normalizes the LLM's response and maps it to a declared
     * choice key. Tries exact match first, then case-insensitive trim match.
     * Returns {@code config.defaultChoice()} when no match is found.
     */
    static String resolveChoice(String rawResponse, RouterStepConfig config) {
        if (rawResponse == null || config.choices() == null) {
            return config.defaultChoice();
        }
        String stripped = rawResponse.strip();
        if (config.choices().containsKey(stripped)) {
            return stripped;
        }
        String lower = stripped.toLowerCase();
        for (String key : config.choices().keySet()) {
            if (key.equalsIgnoreCase(lower)) {
                return key;
            }
        }
        log.debug("LLM selector returned '{}' which is not a declared choice; using defaultChoice='{}'",
                rawResponse, config.defaultChoice());
        return config.defaultChoice();
    }
}
