package ai.operativus.agentmanager.compute.extensions;

import ai.operativus.agentmanager.core.security.SsrfGuard;
import ai.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * A native framework implementation of the WorkflowStepExecutorExtension.
 * This class matches any AgentID configured with a "http://" or "https://" prefix
 * and natively routes the LLM context flow to that external webhook (Python/JS/Rust sandbox).
 *
 * @architecture Registered as a Spring @Component bean, auto-injected into WorkflowService
 *               via constructor injection of List<WorkflowStepExecutorExtension>.
 */
@Component
public class WebhookWorkflowStepExecutor implements WorkflowStepExecutorExtension {

    private static final Logger log = LoggerFactory.getLogger(WebhookWorkflowStepExecutor.class);
    
    // Configured for Virtual Thread blocking context. Do not use async components here.
    private final HttpClient httpClient;

    /**
     * SSRF guard escape hatch — production default {@code false} (block loopback / RFC-1918
     * / link-local). The test profile (application-test.properties) sets {@code true} so
     * runtime tests can POST to a WireMock listener on {@code localhost:<dynamic-port>}.
     * Always-on rejections ({@code 0.0.0.0}, {@code 169.254/16} cloud metadata, non-http(s)
     * schemes) fire regardless of this flag.
     */
    private final boolean allowLoopback;

    public WebhookWorkflowStepExecutor(
            @Value("${agent.workflow.webhook.allow-loopback-urls:false}") boolean allowLoopback) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.allowLoopback = allowLoopback;
    }

    @Override
    public boolean supports(String executorId) {
        return executorId != null && (executorId.startsWith("http://") || executorId.startsWith("https://"));
    }

    @Override
    public String executeStep(String executorId, String workflowId, String runId, String inputPayload, Map<String, Object> context) {
        log.debug("Dispatching Webhook execution to {}", executorId);

        // SSRF guard — the executorId is operator-supplied per workflow step config. Without
        // this check, a workflow could POST to http://169.254.169.254/... (cloud metadata) or
        // any RFC-1918 address reachable from the AGM host. JDK HttpClient does not validate.
        String ssrfError = SsrfGuard.validate(executorId, allowLoopback);
        if (ssrfError != null) {
            log.warn("Webhook workflow-step execution rejected — SSRF guard: {} (url={})",
                    ssrfError, executorId);
            throw new RuntimeException("Webhook URL rejected by SSRF guard: " + ssrfError);
        }

        try {
            // Build the payload bridging DTO
            String jsonPayload = String.format("{\"workflowId\":\"%s\",\"runId\":\"%s\",\"input\":\"%s\"}", 
                    workflowId, runId, inputPayload.replace("\"", "\\\""));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(executorId))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "AgentManager-Webhook-Executor")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // The python/JS endpoint should logically return the modified output sequence.
                return response.body();
            } else {
                throw new RuntimeException("Webhook HTTP Request failed with Status " + response.statusCode() + " - " + response.body());
            }

        } catch (Exception e) {
            log.error("Failed to execute external webhook workflow step against: {}", executorId, e);
            throw new RuntimeException("Extension Webhook execution failed.", e);
        }
    }
}
