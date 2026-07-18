package com.operativus.agentmanager.compute.tools.composio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.ComposioCatalogAction;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Domain Responsibility: Thin client over the upstream Composio catalog API.
 *   Reads the catalog so AGM can show operators what actions Composio offers
 *   and bulk-import them into {@code composio_action_config}. Distinct from
 *   {@link ComposioToolCallback} which is the per-action <em>execute</em> path.
 *
 *   <p><strong>Endpoint path is configurable</strong> via
 *   {@code agent.tools.composio.catalog-list-path} (default
 *   {@code /api/v2/actions/list}). Composio's API has shifted shapes across
 *   v2/v3; the property lets operators retarget without a recompile.
 *
 *   <p><strong>Response shape tolerance:</strong> Composio has used
 *   bare-array, {@code {items: []}}, and {@code {items, totalItems, nextCursor}}
 *   shapes across versions. {@link #listActions} accepts any of those — it
 *   walks the JSON tree looking for an array, then maps each element through
 *   the {@link ComposioCatalogAction} record's {@code @JsonAlias}-tolerant
 *   parser.
 *
 *   <p><strong>Defensive failure modes:</strong>
 *   <ul>
 *     <li>API key blank → returns empty list + INFO log (matches the rest of
 *         the Composio adapter's "credential missing = disabled" contract).</li>
 *     <li>Upstream HTTP error → returns empty + WARN log with status code.</li>
 *     <li>JSON parse error → returns empty + WARN log with truncated payload
 *         (capped at 256 chars to avoid log flooding on malformed responses).</li>
 *   </ul>
 *   None of these throw — the bulk-import flow needs a usable result, and
 *   operators get visibility via the log + the empty response body.
 *
 *   <p>Reuses the singleton {@code composioWebClient} bean (same connect pool
 *   + timeouts as the execute path) and the {@code composioCircuitBreaker}
 *   (a sustained catalog outage trips the breaker shared with the execute
 *   path — both are "Composio is sick").
 *
 * State: Stateless (Spring bean)
 */
@Component
public class ComposioCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(ComposioCatalogClient.class);
    private static final int MAX_LOGGED_PAYLOAD_CHARS = 256;
    private static final TypeReference<List<ComposioCatalogAction>> LIST_TYPE = new TypeReference<>() {};

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final String apiKey;
    private final String catalogListPath;
    private final int defaultLimit;

    public ComposioCatalogClient(
            @Qualifier("composioWebClient") WebClient webClient,
            ObjectMapper objectMapper,
            @Qualifier("composioCircuitBreaker") CircuitBreaker circuitBreaker,
            @Value("${agent.tools.composio.api-key:}") String apiKey,
            @Value("${agent.tools.composio.catalog-list-path:/api/v2/actions/list}") String catalogListPath,
            @Value("${agent.tools.composio.catalog-default-limit:200}") int defaultLimit) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.apiKey = apiKey;
        this.catalogListPath = catalogListPath;
        this.defaultLimit = defaultLimit;
    }

    /**
     * Fetch a page of catalog actions, optionally filtered by {@code app}.
     * Limit is clamped to {@code [1, 1000]} to bound response size; the default
     * comes from {@code agent.tools.composio.catalog-default-limit}.
     *
     * @return parsed actions; never null. Empty on any failure (see class doc).
     */
    public List<ComposioCatalogAction> listActions(String app, Integer limit) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("ComposioCatalogClient: api-key unset; returning empty catalog.");
            return Collections.emptyList();
        }
        int effectiveLimit = clampLimit(limit);
        String uri = buildListUri(app, effectiveLimit);

        try {
            String body = circuitBreaker.executeCallable(() ->
                    webClient.get()
                            .uri(uri)
                            .header("X-API-Key", apiKey)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(60)));
            return parse(body);
        } catch (WebClientResponseException ex) {
            log.warn("ComposioCatalogClient: upstream returned {} {} — returning empty catalog.",
                    ex.getStatusCode().value(), ex.getStatusCode());
            return Collections.emptyList();
        } catch (Exception ex) {
            log.warn("ComposioCatalogClient: catalog fetch failed ({}); returning empty catalog.",
                    ex.toString());
            return Collections.emptyList();
        }
    }

    /** Package-private for testing — tolerant of Composio's three known envelope shapes. */
    List<ComposioCatalogAction> parse(String body) {
        if (body == null || body.isBlank()) return Collections.emptyList();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode arr = extractArrayNode(root);
            if (arr == null || !arr.isArray()) {
                log.warn("ComposioCatalogClient: no array found in response (shape unexpected); "
                        + "first {} chars: {}", MAX_LOGGED_PAYLOAD_CHARS, truncate(body));
                return Collections.emptyList();
            }
            List<ComposioCatalogAction> raw = objectMapper.convertValue(arr, LIST_TYPE);
            // Filter out entries with no usable name — defensive against partial upstream rows.
            List<ComposioCatalogAction> filtered = new ArrayList<>(raw.size());
            for (ComposioCatalogAction a : raw) {
                if (a != null && a.name() != null && !a.name().isBlank()) {
                    filtered.add(a);
                }
            }
            return filtered;
        } catch (Exception ex) {
            log.warn("ComposioCatalogClient: parse failed ({}); first {} chars: {}",
                    ex.toString(), MAX_LOGGED_PAYLOAD_CHARS, truncate(body));
            return Collections.emptyList();
        }
    }

    /**
     * Pull the action array out of any of:
     * <ul>
     *   <li>bare top-level array: {@code [{...},{...}]}</li>
     *   <li>v2-style envelope: {@code {"items":[...]}}</li>
     *   <li>v3-style envelope: {@code {"items":[...],"totalItems":N,"nextCursor":"..."}}</li>
     *   <li>alternative naming: {@code {"actions":[...]}} or {@code {"data":[...]}}</li>
     * </ul>
     */
    private static JsonNode extractArrayNode(JsonNode root) {
        if (root == null) return null;
        if (root.isArray()) return root;
        for (String key : new String[]{"items", "actions", "data", "results"}) {
            JsonNode candidate = root.get(key);
            if (candidate != null && candidate.isArray()) return candidate;
        }
        return null;
    }

    private String buildListUri(String app, int limit) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(catalogListPath)
                .queryParam("limit", limit);
        if (app != null && !app.isBlank()) {
            // Composio's v2 expects appNames=slack,github; v3 expects toolkit=slack — send
            // both so either flavour hits the right filter. The upstream ignores unknowns.
            b.queryParam("appNames", app.trim().toLowerCase());
            b.queryParam("toolkit", app.trim().toLowerCase());
        }
        return b.toUriString();
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return defaultLimit;
        return Math.min(limit, 1000);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_LOGGED_PAYLOAD_CHARS ? s : s.substring(0, MAX_LOGGED_PAYLOAD_CHARS) + "...[truncated]";
    }
}
