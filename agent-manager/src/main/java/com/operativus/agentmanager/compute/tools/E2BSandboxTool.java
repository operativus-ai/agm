package com.operativus.agentmanager.compute.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.security.RequiresCapability;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain Responsibility: Executes Python code in an isolated remote sandbox (E2B), as a parallel
 * option to the in-process {@link PythonCodeInterpreterTool} for heavy dependencies, longer
 * runtimes (up to 5 min), or stronger isolation.
 * State: Stateless. WebClient + ObjectMapper are constructor-injected singletons.
 *
 * Per spec agm-tools-e2b-remote-sandbox.md (T1.2): output capped at 1 MB combined; transient 5xx
 * returns structured error; missing credential throws; Tier 3 HITL gates execution via
 * HitlAdvisor's DESTRUCTIVE_TOOLS set.
 */
@AgentToolComponent
public class E2BSandboxTool {

    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    static final int MIN_TIMEOUT_SECONDS = 1;
    static final int MAX_TIMEOUT_SECONDS = 300;
    static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024; // 512 KB per channel = 1 MB combined cap
    static final long OUTPUT_TOTAL_CAP_BYTES = 1024L * 1024L; // 1 MB combined

    private final WebClient e2bWebClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public E2BSandboxTool(
            @Qualifier("e2bWebClient") WebClient e2bWebClient,
            ObjectMapper objectMapper,
            @Value("${agent.tools.e2b.api-key:}") String apiKey) {
        this.e2bWebClient = e2bWebClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    @RequiresCapability("code_execution")
    @Tool(description = "Execute Python code in an isolated remote sandbox (E2B). Best for: heavy dependencies (pandas/numpy/scikit-learn), longer runtimes (up to 5 minutes), or stronger isolation than the in-process Python interpreter. For fast, dependency-free Python, prefer run_python instead.")
    public String e2b_execute_python(
            @ToolParam(description = "The Python code to execute. Do not wrap in markdown blocks.") String code,
            @ToolParam(required = false, description = "Timeout in seconds (default 30, min 1, max 300).") Integer timeout
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessValidationException(
                    "E2B API key is not configured. Set the E2B_API_KEY environment variable or agent.tools.e2b.api-key property.");
        }

        int effectiveTimeout = clampTimeout(timeout);
        long startMs = System.currentTimeMillis();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("code", code);
        requestBody.put("language", "python");
        requestBody.put("timeoutSeconds", effectiveTimeout);

        try {
            String responseBody = e2bWebClient.post()
                    .uri("/sandboxes/execute")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(effectiveTimeout + 5));

            return interpretResponse(responseBody, startMs);
        } catch (WebClientResponseException ex) {
            return handleHttpError(ex, startMs);
        }
    }

    private static int clampTimeout(Integer timeout) {
        if (timeout == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        if (timeout < MIN_TIMEOUT_SECONDS) {
            return MIN_TIMEOUT_SECONDS;
        }
        if (timeout > MAX_TIMEOUT_SECONDS) {
            return MAX_TIMEOUT_SECONDS;
        }
        return timeout;
    }

    private String interpretResponse(String responseBody, long startMs) {
        long durationMs = System.currentTimeMillis() - startMs;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);

            String stdout = stringValue(parsed.get("stdout"));
            String stderr = stringValue(parsed.get("stderr"));
            Object exitCodeRaw = parsed.get("exitCode");

            // Provider-side timeout — surface as structured TIMEOUT (not throw)
            if ("TIMEOUT".equals(exitCodeRaw) || Boolean.TRUE.equals(parsed.get("timedOut"))) {
                return jsonResult(buildTimeoutResult(durationMs, parsed));
            }

            // Output size cap — truncate before returning to LLM
            long combined = (long) stdout.length() + (long) stderr.length();
            if (combined > OUTPUT_TOTAL_CAP_BYTES) {
                return jsonResult(buildTruncatedResult(stdout, stderr, durationMs));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exitCode", exitCodeRaw == null ? "0" : exitCodeRaw.toString());
            result.put("stdout", stdout);
            result.put("stderr", stderr);
            result.put("durationMs", durationMs);
            result.put("truncated", false);
            return jsonResult(result);
        } catch (Exception parseEx) {
            // Malformed response from E2B — structured error, not throw
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "provider_response_invalid");
            err.put("provider", "e2b");
            err.put("durationMs", durationMs);
            return jsonResult(err);
        }
    }

    private String handleHttpError(WebClientResponseException ex, long startMs) {
        long durationMs = System.currentTimeMillis() - startMs;
        int status = ex.getStatusCode().value();

        // Transient 5xx — structured error, no internal retry
        if (status == 502 || status == 503 || status == 504) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "provider_unavailable");
            err.put("provider", "e2b");
            err.put("statusCode", status);
            String retryAfter = ex.getHeaders().getFirst("Retry-After");
            if (retryAfter != null) {
                try {
                    err.put("retryAfterSeconds", Integer.parseInt(retryAfter));
                } catch (NumberFormatException ignored) {
                    // Retry-After can be a date; skip if not parseable as seconds
                }
            }
            err.put("durationMs", durationMs);
            return jsonResult(err);
        }

        // 4xx (auth / validation) — surface as structured error too; LLM gets context
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "provider_request_failed");
        err.put("provider", "e2b");
        err.put("statusCode", status);
        err.put("durationMs", durationMs);
        return jsonResult(err);
    }

    private static Map<String, Object> buildTimeoutResult(long durationMs, Map<String, Object> parsed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("exitCode", "TIMEOUT");
        r.put("stdout", stringValue(parsed.getOrDefault("stdout", "")));
        r.put("stderr", "Execution exceeded sandbox timeout");
        r.put("durationMs", durationMs);
        r.put("truncated", false);
        return r;
    }

    private static Map<String, Object> buildTruncatedResult(String stdout, String stderr, long durationMs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("exitCode", "OUTPUT_TOO_LARGE");
        r.put("stdout", stdout.length() > OUTPUT_TRUNCATE_BYTES ? stdout.substring(0, OUTPUT_TRUNCATE_BYTES) : stdout);
        r.put("stderr", stderr.length() > OUTPUT_TRUNCATE_BYTES ? stderr.substring(0, OUTPUT_TRUNCATE_BYTES) : stderr);
        r.put("durationMs", durationMs);
        r.put("truncated", true);
        return r;
    }

    private static String stringValue(Object o) {
        return o == null ? "" : o.toString();
    }

    private String jsonResult(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            // Should never happen for plain Map<String, Object>; fall through to a minimal manual JSON.
            return "{\"error\":\"result_serialization_failed\",\"provider\":\"e2b\"}";
        }
    }
}
