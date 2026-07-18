package ai.operativus.agentmanager.compute.tools.composio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.ComposioConnectionConfigRepository;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.ComposioConnectionConfig;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain Responsibility: Spring AI {@link ToolCallback} for a single Composio action. Each
 * Composio-enabled action gets one instance. Forwards LLM tool invocations to Composio's
 * REST execute endpoint, handles all error paths per spec, and returns a structured JSON
 * string to the LLM. Wraps the call in a shared Resilience4j circuit breaker so a Composio
 * outage opens the breaker once for the whole adapter (audit Finding 3).
 * State: Stateless (immutable after construction).
 *
 * <p>Per agm-agentos-tool-parity-impl.md §3 (acceptance criteria) and §4 (architectural
 * decisions) — auth header is {@code X-API-Key} (NOT Bearer); base URL set on the injected
 * WebClient (PR2 {@code composioWebClient} bean); 1 MB output cap; structured errors for
 * all LLM-recoverable failures; {@link BusinessValidationException} only for hard-fail
 * (impossible state).</p>
 */
public class ComposioToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ComposioToolCallback.class);

    static final long OUTPUT_TOTAL_CAP_BYTES = 1024L * 1024L; // 1 MB
    static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(65); // tracks WebClient read-timeout 60s

    private final String composioActionName;     // e.g., "NOTION_CREATE_PAGE"
    private final String llmToolName;            // e.g., "composio_notion_create_page"
    private final String description;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final String apiKey;
    private final Environment environment;
    private final ComposioConnectionConfigRepository connectionRepository;

    public ComposioToolCallback(
            String composioActionName,
            String description,
            WebClient webClient,
            ObjectMapper objectMapper,
            CircuitBreaker circuitBreaker,
            String apiKey,
            Environment environment,
            ComposioConnectionConfigRepository connectionRepository) {
        this.composioActionName = composioActionName;
        this.llmToolName = ComposioTierResolver.TOOL_NAME_PREFIX + composioActionName.toLowerCase();
        this.description = description;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.apiKey = apiKey;
        this.environment = environment;
        this.connectionRepository = connectionRepository;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(llmToolName)
                .description(description)
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}")
                .build();
    }

    @Override
    public String call(String toolInput) {
        long startMs = System.currentTimeMillis();

        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessValidationException(
                    "Composio API key is not configured. Set the COMPOSIO_API_KEY environment variable.");
        }

        String orgId = AgentContextHolder.getOrgId();
        String connectionId = resolveConnectionId(orgId);
        if (connectionId == null || connectionId.isBlank()) {
            return jsonError(Map.of(
                    "error", "connection_missing",
                    "provider", "composio",
                    "action", composioActionName,
                    "message", "Operator must connect this app in the Composio dashboard for this org",
                    "durationMs", System.currentTimeMillis() - startMs));
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("connectionId", connectionId);
        try {
            requestBody.put("input", objectMapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput));
        } catch (JsonProcessingException ex) {
            return jsonError(Map.of(
                    "error", "tool_input_invalid_json",
                    "provider", "composio",
                    "action", composioActionName,
                    "durationMs", System.currentTimeMillis() - startMs));
        }

        try {
            String responseBody = circuitBreaker.executeCallable(() ->
                    webClient.post()
                            .uri("/api/v2/actions/{action}/execute", composioActionName)
                            .header("X-API-Key", apiKey)
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(BLOCK_TIMEOUT));
            return interpretResponse(responseBody, startMs);
        } catch (CallNotPermittedException ex) {
            return jsonError(Map.of(
                    "error", "provider_circuit_open",
                    "provider", "composio",
                    "retryAfterSeconds", 30,
                    "durationMs", System.currentTimeMillis() - startMs));
        } catch (WebClientResponseException ex) {
            return handleHttpError(ex, startMs);
        } catch (WebClientRequestException ex) {
            log.warn("composio.request_failed action={} cause={}", composioActionName, ex.getClass().getSimpleName());
            return jsonError(Map.of(
                    "error", "provider_unreachable",
                    "provider", "composio",
                    "cause", ex.getClass().getSimpleName(),
                    "durationMs", System.currentTimeMillis() - startMs));
        } catch (Exception ex) {
            log.warn("composio.unexpected_failure action={} cause={}", composioActionName, ex.getClass().getSimpleName());
            return jsonError(Map.of(
                    "error", "provider_request_failed",
                    "provider", "composio",
                    "cause", ex.getClass().getSimpleName(),
                    "durationMs", System.currentTimeMillis() - startMs));
        }
    }

    /**
     * Resolve the Composio bundled-OAuth connectionId for the given org. DB &gt; properties
     * precedence: query {@code composio_connection_config} first, fall back to the
     * {@code agent.tools.composio.connection-ids.<orgId>} property only when no row exists.
     * A DB read failure is logged at WARN and falls through to the property — observability,
     * not authority, so a transient DB hiccup must not break tool execution paths that have
     * a working properties-file connection.
     */
    private String resolveConnectionId(String orgId) {
        if (orgId == null) {
            return null;
        }
        if (connectionRepository != null) {
            try {
                String fromDb = connectionRepository.findByOrgId(orgId)
                        .map(ComposioConnectionConfig::getConnectionId)
                        .orElse(null);
                if (fromDb != null && !fromDb.isBlank()) {
                    return fromDb;
                }
            } catch (Exception ex) {
                log.warn("Composio connection DB-read failed for org={}; falling back to properties (cause={})",
                        orgId, ex.getClass().getSimpleName());
            }
        }
        return environment.getProperty("agent.tools.composio.connection-ids." + orgId);
    }

    private String interpretResponse(String responseBody, long startMs) {
        long durationMs = System.currentTimeMillis() - startMs;
        if (responseBody != null && responseBody.length() > OUTPUT_TOTAL_CAP_BYTES) {
            return jsonError(Map.of(
                    "exitCode", "OUTPUT_TOO_LARGE",
                    "action", composioActionName,
                    "truncated", true,
                    "body", responseBody.substring(0, OUTPUT_TRUNCATE_BYTES),
                    "durationMs", durationMs));
        }
        try {
            JsonNode parsed = objectMapper.readTree(responseBody == null ? "{}" : responseBody);
            // Pass-through Composio's response with adapter-injected metadata
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", "composio");
            result.put("action", composioActionName);
            result.put("durationMs", durationMs);
            result.put("response", parsed);
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            return jsonError(Map.of(
                    "error", "provider_response_invalid",
                    "provider", "composio",
                    "action", composioActionName,
                    "durationMs", durationMs));
        }
    }

    private String handleHttpError(WebClientResponseException ex, long startMs) {
        long durationMs = System.currentTimeMillis() - startMs;
        int status = ex.getStatusCode().value();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("provider", "composio");
        err.put("statusCode", status);
        err.put("durationMs", durationMs);

        String retryAfter = ex.getHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                err.put("retryAfterSeconds", Integer.parseInt(retryAfter));
            } catch (NumberFormatException ignored) {
                // header may be HTTP date — skip; LLM gets statusCode only
            }
        }

        if (status == 429) {
            err.put("error", "provider_rate_limited");
        } else if (status == 502 || status == 503 || status == 504) {
            err.put("error", "provider_unavailable");
        } else {
            err.put("error", "provider_request_failed");
        }
        return jsonError(err);
    }

    private String jsonError(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            return "{\"error\":\"result_serialization_failed\",\"provider\":\"composio\"}";
        }
    }

    String getLlmToolName() {
        return llmToolName;
    }
}
