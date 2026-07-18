package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.core.model.WorkflowConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * Domain Responsibility: The single workflow CONDITION / LOOP-{@code until:} grammar (REQ-DR-6),
 *     shared by BOTH dispatch engines — the flat {@code WorkflowService} loop and the DAG
 *     {@code ConditionNodeExecutor} / {@code LoopNodeExecutor}. Extracted because the two engines
 *     had diverged: the flat engine implemented {@code jsonpath:} + {@code llm:} while the DAG
 *     executors silently fell through to {@code true}, so a workflow using those prefixes
 *     mis-routed on the (now default) DAG path. One implementation removes that drift class.
 *
 *     <p>Supported prefixes (case-insensitive prefix match; the value preserves case):
 *     <ul>
 *       <li>{@code contains:<text>} / {@code not_contains:<text>} — case-insensitive substring</li>
 *       <li>{@code length>N} / {@code length<N} — numeric length compare (parse error → false + WARN)</li>
 *       <li>{@code jsonpath:<expr>} — Jayway JSONPath against the prior output; non-JSON input is
 *           wrapped as {@code {"text": ...}} (the {@link RuleRouteSelector} strategy) so {@code $.text}
 *           works on free-form text. Truthy resolution = true; PathNotFound / parse error = false.</li>
 *       <li>{@code llm:<yes/no question>} — yes/no judgment via {@link LlmConditionEvaluator};
 *           failures evaluate to false defensively.</li>
 *       <li>{@code not_empty} / {@code empty} — input blank check</li>
 *     </ul>
 *
 *     <p><b>Null contract:</b> a null expression or a null input evaluates to {@code true} —
 *     an absent gate / absent data passes through (not a malformed predicate).
 *
 *     <p><b>Fail-closed contract (DAG-4d):</b> a recognized-but-unparseable expression
 *     (e.g. a non-numeric {@code length>} bound) or an unrecognized prefix evaluates to
 *     {@code false} + WARN. For a CONDITION (skip-on-false) that skips the guarded branch
 *     rather than activating one the author never intended; for a LOOP {@code until:}
 *     (stop-on-true) it keeps looping to the {@code max:} cap rather than stopping on a bound
 *     that does not actually hold. The {@code llm:} no-evaluator path is fail-closed the same way.
 * State: Stateless (Spring singleton).
 */
@Component
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    private final LlmConditionEvaluator llmConditionEvaluator;

    public ConditionEvaluator(LlmConditionEvaluator llmConditionEvaluator) {
        this.llmConditionEvaluator = llmConditionEvaluator;
    }

    /** Evaluates a workflow condition expression against the current/prior step output. */
    public boolean evaluate(String conditionExpr, String input) {
        if (conditionExpr == null || input == null) return true;
        String raw = conditionExpr.trim();
        String lower = raw.toLowerCase();

        if (lower.startsWith("contains:")) {
            return input.toLowerCase().contains(raw.substring("contains:".length()).trim().toLowerCase());
        }
        if (lower.startsWith("not_contains:")) {
            return !input.toLowerCase().contains(raw.substring("not_contains:".length()).trim().toLowerCase());
        }
        if (lower.startsWith("length>")) {
            try { return input.length() > Integer.parseInt(raw.substring("length>".length()).trim()); }
            catch (NumberFormatException e) {
                log.warn("Unparseable 'length>' bound in condition '{}'; evaluating to false (fail-closed).", conditionExpr);
                return false;
            }
        }
        if (lower.startsWith("length<")) {
            try { return input.length() < Integer.parseInt(raw.substring("length<".length()).trim()); }
            catch (NumberFormatException e) {
                log.warn("Unparseable 'length<' bound in condition '{}'; evaluating to false (fail-closed).", conditionExpr);
                return false;
            }
        }
        if (lower.startsWith("jsonpath:")) {
            return evaluateJsonPath(raw.substring("jsonpath:".length()).trim(), input);
        }
        if (lower.startsWith("llm:")) {
            return llmConditionEvaluator != null
                    && llmConditionEvaluator.evaluate(raw.substring("llm:".length()).trim(), input);
        }
        if (WorkflowConstants.CONDITION_EXPR_NOT_EMPTY.equals(lower)) return !input.isBlank();
        if (WorkflowConstants.CONDITION_EXPR_EMPTY.equals(lower)) return input.isBlank();
        log.warn("Unknown condition expression: '{}'; evaluating to false (fail-closed).", conditionExpr);
        return false;
    }

    /**
     * Jayway JSONPath against the prior step output. The wrapped-text fallback matches
     * {@link RuleRouteSelector} so authors can use {@code $.text} on free-form agent output.
     * PathNotFound / parse error resolves to false (defensive: a malformed path skips the next
     * step / keeps looping rather than throwing mid-dispatch).
     */
    private boolean evaluateJsonPath(String expression, String input) {
        if (expression == null || expression.isBlank()) {
            log.warn("Blank jsonpath: expression; evaluating to false");
            return false;
        }
        String trimmed = input.trim();
        String document = (trimmed.startsWith("{") || trimmed.startsWith("["))
                ? input
                : "{\"text\":" + jsonStringEscape(input) + "}";
        try {
            Object resolved = com.jayway.jsonpath.JsonPath.parse(document).read(expression);
            return isTruthy(resolved);
        } catch (com.jayway.jsonpath.PathNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            log.warn("jsonpath: '{}' failed ({}); evaluating to false",
                    expression, e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0d;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    private static String jsonStringEscape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
