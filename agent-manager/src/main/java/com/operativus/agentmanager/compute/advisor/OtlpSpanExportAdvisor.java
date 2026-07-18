package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetricConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Domain Responsibility: Translates Spring AI ChatClient call/stream lifecycle events into
 * OpenTelemetry spans with LLM-specific semantic attributes (agent ID, model, token usage).
 * Spans are exported asynchronously via the {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor}
 * configured in {@link com.operativus.agentmanager.compute.config.OtlpExportConfig}.
 *
 * <p>Privacy: By default, raw prompts and completions are NOT included in span attributes.
 * Set {@code agentmanager.otlp.include-prompts=true} to enable (for dev/staging only).</p>
 *
 * <p>Ordering: Runs at {@code Integer.MAX_VALUE - 1} (innermost, just before GenAiMetricsAdvisor)
 * so that it captures the actual model response after all other advisors have processed.</p>
 *
 * State: Stateless
 */
@Component
@ConditionalOnBean(name = "otlpGrpcSpanExporter")
public class OtlpSpanExportAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(OtlpSpanExportAdvisor.class);

    // LLM semantic convention attribute keys
    private static final AttributeKey<String> ATTR_AGENT_ID = AttributeKey.stringKey(MetricConstants.ATTR_AGENT_ID);
    private static final AttributeKey<String> ATTR_RUN_ID = AttributeKey.stringKey(MetricConstants.ATTR_RUN_ID);
    private static final AttributeKey<String> ATTR_SESSION_ID = AttributeKey.stringKey(MetricConstants.ATTR_SESSION_ID);
    private static final AttributeKey<String> ATTR_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long> ATTR_PROMPT_TOKENS = AttributeKey.longKey("gen_ai.usage.prompt_tokens");
    private static final AttributeKey<Long> ATTR_COMPLETION_TOKENS = AttributeKey.longKey("gen_ai.usage.completion_tokens");
    private static final AttributeKey<Long> ATTR_TOTAL_TOKENS = AttributeKey.longKey("gen_ai.usage.total_tokens");
    private static final AttributeKey<Long> ATTR_LATENCY_MS = AttributeKey.longKey(MetricConstants.ATTR_LATENCY_MS);
    private static final AttributeKey<String> ATTR_PROMPT = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> ATTR_COMPLETION = AttributeKey.stringKey("gen_ai.completion");

    private final Tracer tracer;
    private final boolean includePrompts;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer, tag {@code advisor=otlp_span_export}. */
    private final Timer durationTimer;

    public OtlpSpanExportAdvisor(Tracer tracer,
                                  @Value("${agentmanager.otlp.include-prompts:false}") boolean includePrompts,
                                  MeterRegistry meterRegistry) {
        this.tracer = tracer;
        this.includePrompts = includePrompts;
        this.durationTimer = Timer.builder(MetricConstants.ADVISOR_DURATION_MS)
                .tag("advisor", "otlp_span_export").register(meterRegistry);
        log.info("OtlpSpanExportAdvisor initialized (includePrompts={})", includePrompts);
    }

    @Override
    public String getName() {
        return "OtlpSpanExportAdvisor";
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            String agentId = resolveAgentId(request);
            Span span = tracer.spanBuilder(MetricConstants.SPAN_LLM_CALL)
                    .setAttribute(ATTR_AGENT_ID, agentId)
                    .startSpan();

            enrichSpanContext(span);

            long startNanos = System.nanoTime();
            try {
                ChatClientResponse response = chain.nextCall(request);
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

                enrichSpanWithResponse(span, response, latencyMs);

                if (includePrompts) {
                    enrichSpanWithPromptData(span, request, response);
                }

                span.end();
                return response;
            } catch (Exception e) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                span.setAttribute(ATTR_LATENCY_MS, latencyMs);
                span.recordException(e);
                span.end();
                throw e;
            }
        });
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String agentId = resolveAgentId(request);

        return Flux.defer(() -> {
            Span span = tracer.spanBuilder(MetricConstants.SPAN_LLM_STREAM)
                    .setAttribute(ATTR_AGENT_ID, agentId)
                    .startSpan();

            enrichSpanContext(span);

            long startNanos = System.nanoTime();

            return chain.nextStream(request)
                    .doOnComplete(() -> {
                        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                        span.setAttribute(ATTR_LATENCY_MS, latencyMs);
                        span.end();
                    })
                    .doOnError(e -> {
                        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                        span.setAttribute(ATTR_LATENCY_MS, latencyMs);
                        span.recordException(e);
                        span.end();
                    });
        });
    }

    private void enrichSpanContext(Span span) {
        String runId = AgentContextHolder.getCurrentRunId();
        if (runId != null) span.setAttribute(ATTR_RUN_ID, runId);

        String sessionId = AgentContextHolder.getSessionId();
        if (sessionId != null) span.setAttribute(ATTR_SESSION_ID, sessionId);
    }

    private void enrichSpanWithResponse(Span span, ChatClientResponse response, long latencyMs) {
        span.setAttribute(ATTR_LATENCY_MS, latencyMs);

        if (response != null && response.chatResponse() != null && response.chatResponse().getMetadata() != null) {
            String model = response.chatResponse().getMetadata().getModel();
            if (model != null) span.setAttribute(ATTR_MODEL, model);

            Usage usage = response.chatResponse().getMetadata().getUsage();
            if (usage != null) {
                Integer promptTokens = usage.getPromptTokens();
                Integer completionTokens = usage.getCompletionTokens();
                Integer totalTokens = usage.getTotalTokens();
                if (promptTokens != null) span.setAttribute(ATTR_PROMPT_TOKENS, promptTokens.longValue());
                if (completionTokens != null) span.setAttribute(ATTR_COMPLETION_TOKENS, completionTokens.longValue());
                if (totalTokens != null) span.setAttribute(ATTR_TOTAL_TOKENS, totalTokens.longValue());
            }
        }
    }

    private void enrichSpanWithPromptData(Span span, ChatClientRequest request, ChatClientResponse response) {
        try {
            if (request.prompt() != null && request.prompt().getContents() != null) {
                String promptText = request.prompt().getContents().toString();
                // Truncate to avoid oversized spans
                if (promptText.length() > 4096) {
                    promptText = promptText.substring(0, 4096) + "... [truncated]";
                }
                span.setAttribute(ATTR_PROMPT, promptText);
            }

            if (response != null && response.chatResponse() != null
                    && response.chatResponse().getResult() != null
                    && response.chatResponse().getResult().getOutput() != null) {
                String completionText = response.chatResponse().getResult().getOutput().getText();
                if (completionText != null) {
                    if (completionText.length() > 4096) {
                        completionText = completionText.substring(0, 4096) + "... [truncated]";
                    }
                    span.setAttribute(ATTR_COMPLETION, completionText);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to enrich span with prompt data: {}", e.getMessage());
        }
    }

    private String resolveAgentId(ChatClientRequest request) {
        if (request.context() != null && request.context().containsKey("agentId")) {
            return String.valueOf(request.context().get("agentId"));
        }
        String scopedAgentId = AgentContextHolder.getAgentId();
        return scopedAgentId != null ? scopedAgentId : "unknown";
    }
}
