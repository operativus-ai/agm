package ai.operativus.agentmanager.compute.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: REQ-DR-6 — evaluates an operator-authored boolean
 *     judgment via the primary LLM. Used by the workflow CONDITION step
 *     dispatcher when an expression carries the {@code llm:} prefix.
 *     The expression text is treated as a yes/no question; the model's
 *     response is normalized and mapped to {@code true} (yes / true / y / 1)
 *     or {@code false} (anything else).
 *
 *     <p>Sibling to {@link LlmRouteSelector} — same ChatClient.Builder
 *     pattern, no advisor chain. Failures (provider hiccup, blank
 *     response) return {@code false} defensively so the workflow falls
 *     through to the skip path rather than throwing mid-dispatch.
 *
 * State: Stateful (caches a built ChatClient at construction)
 */
@Component
public class LlmConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LlmConditionEvaluator.class);

    private final ChatClient chatClient;

    public LlmConditionEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Evaluate {@code question} as a yes/no judgment over {@code priorOutput}.
     * Returns {@code false} on any error path so the dispatcher applies its
     * skip-next-step default rather than crashing.
     */
    public boolean evaluate(String question, String priorOutput) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String prompt = buildPrompt(question, priorOutput);
        String raw;
        try {
            raw = chatClient.prompt(prompt).call().content();
        } catch (RuntimeException e) {
            log.warn("LLM condition ChatClient call failed ({}); evaluating to false",
                    e.getClass().getSimpleName());
            return false;
        }
        return parseBoolean(raw);
    }

    static String buildPrompt(String question, String priorOutput) {
        return question
                + "\n\nContext:\n" + (priorOutput == null ? "" : priorOutput)
                + "\n\nAnswer with exactly one word: yes or no. No explanation, no punctuation.";
    }

    static boolean parseBoolean(String response) {
        if (response == null) return false;
        String token = response.strip().toLowerCase();
        // Strip leading punctuation/whitespace and take the first alpha-numeric token.
        int end = 0;
        while (end < token.length() && Character.isLetterOrDigit(token.charAt(end))) end++;
        if (end == 0) return false;
        String first = token.substring(0, end);
        return switch (first) {
            case "yes", "true", "y", "1" -> true;
            default -> false;
        };
    }
}
