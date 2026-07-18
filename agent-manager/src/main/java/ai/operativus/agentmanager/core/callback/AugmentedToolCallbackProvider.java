package ai.operativus.agentmanager.core.callback;

import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.model.ToolCallDTO;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Wraps internal Spring AI tool executions to capture metrics, enforce human-in-the-loop (HITL) approvals, and record explicit ToolCallDTO traces into the AgentContextHolder. Fulfills Requirement 3.1.2.
 *     Also emits TOOL_INVOKED / TOOL_COMPLETED events to {@link AgentRunEventBus} for the run timeline (logging plan §5.12).
 * State: Stateless (Component Wrapper)
 */
@Component
public class AugmentedToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(AugmentedToolCallbackProvider.class);
    private final ai.operativus.agentmanager.compute.service.ToolCompressionService compressionService;
    private final AgentRunEventBus eventBus;
    private final boolean toolContentLoggingEnabled;
    private final ai.operativus.agentmanager.compute.advisor.HitlAdvisor hitlAdvisor;

    public AugmentedToolCallbackProvider(ai.operativus.agentmanager.compute.service.ToolCompressionService compressionService,
                                          AgentRunEventBus eventBus,
                                          @Value("${agentmanager.logging.tool-content:false}") boolean toolContentLoggingEnabled,
                                          ai.operativus.agentmanager.compute.advisor.HitlAdvisor hitlAdvisor) {
        this.compressionService = compressionService;
        this.eventBus = eventBus;
        this.toolContentLoggingEnabled = toolContentLoggingEnabled;
        this.hitlAdvisor = hitlAdvisor;
    }

    // This method wraps a list of original callbacks with tracing logic
    public List<ToolCallback> wrap(List<ToolCallback> originals, ai.operativus.agentmanager.core.model.definitions.AgentDefinition agentDef) {
        List<ToolCallback> wrapped = new ArrayList<>();
        for (ToolCallback original : originals) {
            wrapped.add(new TracingToolCallback(original, compressionService, eventBus, agentDef, toolContentLoggingEnabled, hitlAdvisor));
        }
        return wrapped;
    }

    private static class TracingToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final ai.operativus.agentmanager.compute.service.ToolCompressionService compressor;
        private final AgentRunEventBus eventBus;
        private final ai.operativus.agentmanager.core.model.definitions.AgentDefinition agentDef;
        private final boolean toolContentLoggingEnabled;
        private final ai.operativus.agentmanager.compute.advisor.HitlAdvisor hitlAdvisor;

        public TracingToolCallback(ToolCallback delegate,
                                    ai.operativus.agentmanager.compute.service.ToolCompressionService compressor,
                                    AgentRunEventBus eventBus,
                                    ai.operativus.agentmanager.core.model.definitions.AgentDefinition agentDef,
                                    boolean toolContentLoggingEnabled,
                                    ai.operativus.agentmanager.compute.advisor.HitlAdvisor hitlAdvisor) {
            this.delegate = delegate;
            this.compressor = compressor;
            this.eventBus = eventBus;
            this.agentDef = agentDef;
            this.toolContentLoggingEnabled = toolContentLoggingEnabled;
            this.hitlAdvisor = hitlAdvisor;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            String toolName = delegate.getToolDefinition().name();

            // Dynamic HITL Check. requireApprovalForTool creates the pending-approval row in
            // the DB (carrying runId/sessionId/agentId for the UI to render) and throws
            // ApprovalRequiredException, which the ToolExecutionExceptionProcessor bean
            // (see ToolCallingExceptionConfig) propagates up to AgentService:327 where it
            // becomes RunStatus.PAUSED with requiredAction metadata.
            if (hitlAdvisor.requiresHitl(toolName)) {
                if (!AgentContextHolder.isToolApproved(toolName)) {
                    hitlAdvisor.requireApprovalForTool(
                            toolName,
                            toolInput,
                            AgentContextHolder.getCurrentRunId(),
                            AgentContextHolder.getSessionId(),
                            AgentContextHolder.getAgentId());
                }
            }

            // Log agent identity context if present for this tool execution
            if (AgentIdentityContext.hasIdentity()) {
                log.debug("Tool '{}' executing with agent identity (agent: {}, providers: {})",
                        toolName,
                        AgentIdentityContext.getCredentialAgentId(),
                        AgentIdentityContext.getAllTokens().keySet());
            }

            long start = System.currentTimeMillis();
            String output = null;
            boolean error = false;
            publishToolEvent(AgentRunEventType.TOOL_INVOKED, toolName, buildInvokedPayload(toolName, toolInput));
            // OTel span event — gives Jaeger/Datadog traces a per-tool entry inside the
            // ambient agent.run span. No-op when OTLP is disabled (Span.current() returns
            // INVALID, addEvent is dropped). Input truncated to 300 chars to avoid prompt
            // payloads bloating trace storage.
            Span.current().addEvent("tool.invoke",
                    Attributes.of(
                            AttributeKey.stringKey("tool.name"), toolName,
                            AttributeKey.stringKey("agent.id"),
                                    AgentContextHolder.getAgentId() != null ? AgentContextHolder.getAgentId() : "unknown",
                            AttributeKey.stringKey("input.preview"), truncate(toolInput, 300)));
            try {
                output = delegate.call(toolInput);

                // Compress output context if it exceeds model threshold
                try {
                    output = compressor.compressIfRequired(toolName, output, agentDef);
                } catch (Exception ex) {
                    log.warn("Non-fatal error running ToolCompressionService: {}", ex.getMessage());
                }

                return output;
            } catch (Exception e) {
                // ApprovalRequiredException flows through the standard catch — the
                // ToolExecutionExceptionProcessor bean rethrows it past Spring AI's
                // tool-error handling, so we don't need to short-circuit here.
                output = "Error: " + e.getMessage();
                error = true;
                throw e;
            } finally {
                long duration = System.currentTimeMillis() - start;
                // Metadata-only INFO log — no content, no PII exposure. See logging plan Risk R-7.
                log.info("tool.complete agent={} tool={} durationMs={} status={} inputSize={} outputSize={}",
                        AgentContextHolder.getAgentId() != null ? AgentContextHolder.getAgentId() : "unknown",
                        toolName,
                        duration,
                        error ? "error" : "ok",
                        toolInput != null ? toolInput.length() : 0,
                        output != null ? output.length() : 0);
                // Opt-in content preview for dev/staging — gated by agentmanager.logging.tool-content.
                if (toolContentLoggingEnabled && log.isDebugEnabled()) {
                    log.debug("tool.content tool={} input={} output={}",
                            toolName, truncate(toolInput, 600), truncate(output, 600));
                }
                AgentContextHolder.addToolCall(new ToolCallDTO(
                        toolName,
                        toolInput,
                        output,
                        duration,
                        error
                ));
                publishToolEvent(AgentRunEventType.TOOL_COMPLETED, toolName,
                        buildCompletedPayload(toolName, toolInput, output, duration, error));
                // OTel span event for the matching close — gives Jaeger duration + outcome
                // alongside the tool.invoke event. Output preview is also truncated to keep
                // trace storage bounded.
                Span.current().addEvent("tool.complete",
                        Attributes.of(
                                AttributeKey.stringKey("tool.name"), toolName,
                                AttributeKey.longKey("duration.ms"), duration,
                                AttributeKey.stringKey("status"), error ? "error" : "ok",
                                AttributeKey.stringKey("output.preview"), truncate(output, 300)));
            }
        }

        private Map<String, Object> buildInvokedPayload(String toolName, String toolInput) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("toolName", toolName);
            String agentName = AgentContextHolder.getAgentName();
            if (agentName != null) payload.put("agentName", agentName);
            payload.put("inputLength", toolInput != null ? toolInput.length() : 0);
            payload.put("hasAgentIdentity", AgentIdentityContext.hasIdentity());
            return payload;
        }

        private Map<String, Object> buildCompletedPayload(String toolName, String toolInput, String output,
                                                          long durationMs, boolean error) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("toolName", toolName);
            String agentName = AgentContextHolder.getAgentName();
            if (agentName != null) payload.put("agentName", agentName);
            payload.put("durationMs", durationMs);
            payload.put("status", error ? "error" : "ok");
            payload.put("inputLength", toolInput != null ? toolInput.length() : 0);
            payload.put("outputLength", output != null ? output.length() : 0);
            return payload;
        }

        private void publishToolEvent(AgentRunEventType type, String toolName, Map<String, Object> payload) {
            if (eventBus == null) return;
            String runId = AgentContextHolder.getCurrentRunId();
            if (runId == null) return; // tool invoked outside an agent run — skip timeline event
            try {
                AgentRunEvent event = new AgentRunEvent(
                        type,
                        runId,
                        AgentContextHolder.getAgentId(),
                        null,
                        AgentContextHolder.getSessionId(),
                        AgentContextHolder.getOrgId(),
                        AgentContextHolder.getOrchestrationDepth(),
                        payload,
                        Instant.now());
                eventBus.publish(event);
            } catch (RuntimeException ex) {
                // R-18: isolation — event publication must never break tool execution
                log.warn("Failed to publish {} event for tool={} runId={}", type, toolName, runId, ex);
            }
        }

        private static String truncate(String s, int max) {
            if (s == null) return "null";
            if (s.length() <= max) return s;
            return s.substring(0, max) + "...[truncated " + (s.length() - max) + " chars]";
        }
    }
}
