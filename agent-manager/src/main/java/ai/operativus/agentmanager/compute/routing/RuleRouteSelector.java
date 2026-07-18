package ai.operativus.agentmanager.compute.routing;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import ai.operativus.agentmanager.core.model.RouterStepConfig;
import ai.operativus.agentmanager.core.model.enums.RouteSelectorType;
import ai.operativus.agentmanager.core.spi.RouteSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: RULE-mode {@link RouteSelector} for REQ-DR-4 ROUTER
 *     steps. Evaluates the configured JSONPath expression against the prior
 *     step's output (interpreted as JSON when it parses, otherwise as a
 *     wrapped text payload at {@code $.text}) and returns the matched leaf
 *     value as the choice key.
 *
 *     <p>Behavior:
 *     <ul>
 *       <li>If the JSONPath resolves to a key present in {@code config.choices()},
 *           that key is returned.</li>
 *       <li>If the JSONPath resolves but the value is not a choice key,
 *           {@code config.defaultChoice()} is returned (may be {@code null} —
 *           dispatcher fails the run when null with no match).</li>
 *       <li>If the JSONPath fails (parse error, path-not-found, type mismatch),
 *           {@code config.defaultChoice()} is returned. Defensive: bad JSON in
 *           the prior step output is a content-level concern, not a routing-time
 *           crash.</li>
 *     </ul>
 *
 *     <p>JSONPath flavor: Jayway with {@link Option#SUPPRESS_EXCEPTIONS} disabled
 *     so missing paths surface as {@link PathNotFoundException} (caught here and
 *     mapped to the default branch), and {@link Option#ALWAYS_RETURN_LIST} OFF
 *     so leaf reads return scalars directly.
 *
 * State: Stateless (Spring bean)
 */
@Component
public class RuleRouteSelector implements RouteSelector {

    private static final Logger log = LoggerFactory.getLogger(RuleRouteSelector.class);

    private static final Configuration JSONPATH_CONFIG = Configuration.builder().build();

    @Override
    public RouteSelectorType selectorType() {
        return RouteSelectorType.RULE;
    }

    @Override
    public String selectChoice(RouterStepConfig config, String priorStepOutput) {
        if (config == null) {
            throw new IllegalArgumentException("RuleRouteSelector requires non-null RouterStepConfig");
        }
        String expression = config.selectorExpression();
        if (expression == null || expression.isBlank()) {
            log.warn("RULE selector invoked with blank selectorExpression; falling back to defaultChoice='{}'",
                    config.defaultChoice());
            return config.defaultChoice();
        }
        String document = wrapForJsonPath(priorStepOutput);
        String resolved;
        try {
            Object raw = JsonPath.using(JSONPATH_CONFIG).parse(document).read(expression);
            resolved = raw == null ? null : raw.toString();
        } catch (PathNotFoundException e) {
            log.debug("RULE selector JSONPath '{}' not found in prior step output; using defaultChoice='{}'",
                    expression, config.defaultChoice());
            return config.defaultChoice();
        } catch (RuntimeException e) {
            log.warn("RULE selector JSONPath '{}' failed ({}); using defaultChoice='{}'",
                    expression, e.getClass().getSimpleName(), config.defaultChoice());
            return config.defaultChoice();
        }
        if (resolved != null && config.choices() != null && config.choices().containsKey(resolved)) {
            return resolved;
        }
        log.debug("RULE selector resolved '{}' is not a declared choice key; using defaultChoice='{}'",
                resolved, config.defaultChoice());
        return config.defaultChoice();
    }

    /**
     * Wraps non-JSON prior output as {@code {"text": "..."}} so authors can use
     * {@code $.text contains: ...} on free-text agent output. JSON-shaped output
     * is passed through verbatim so {@code $.decision} works as written.
     */
    private static String wrapForJsonPath(String priorOutput) {
        if (priorOutput == null) {
            return "{}";
        }
        String trimmed = priorOutput.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return priorOutput;
        }
        return "{\"text\":" + jsonString(priorOutput) + "}";
    }

    private static String jsonString(String raw) {
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
