package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.compute.config.AgentMdcFilter;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Domain Responsibility: Structured telemetry advisor that intercepts all ChatClient call and stream boundaries
 * to emit standardized, phase-annotated log events. This advisor populates SLF4J MDC with agentic identity
 * (agentId, runId, phase) from ScopedValues, ensuring every log line within the LLM execution chain carries
 * full execution context without manual string concatenation.
 *
 * <p>Architecture Note: This advisor intentionally logs ONLY structural metadata (agent ID, phase, latency, token counts).
 * It does NOT log raw user prompts, system instructions, or LLM response text to comply with the
 * "Private by Design" security mandate. Raw payloads are handled exclusively by the PII/ContentSafety advisors
 * and persisted in pgvector audit tables.</p>
 *
 * <p>Ordering: This advisor runs at order 0 (outermost) to wrap all inner advisors (PII, PromptInjection,
 * ContentSafety) and capture total end-to-end execution time including all security processing.</p>
 *
 * State: Stateless
 */
@Component
public class AgentLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoggingAdvisor.class);

    private final AgentRunEventBus eventBus;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer. Single name {@code advisor.duration_ms}
     *  with tag {@code advisor=agent_logging} so Grafana pivots by tag. Streaming path is intentionally NOT timed
     *  because reactive lifecycle on the surrounding Flux is the authoritative measurement. */
    private final Timer durationTimer;

    public AgentLoggingAdvisor(AgentRunEventBus eventBus, MeterRegistry meterRegistry) {
        this.eventBus = eventBus;
        this.durationTimer = Timer.builder("advisor.duration_ms")
                .tag("advisor", "agent_logging").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "AgentLoggingAdvisor";
    }

    /**
     * @summary Returns the advisor order. 0 = outermost, wrapping all other advisors.
     * @logic Ensures latency measurements include all inner advisor processing (PII, safety, memory, RAG).
     */
    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * @summary Intercepts synchronous ChatClient calls to emit structured phase logs with latency and token metrics.
     * @logic
     * 1. Reads agentId from the ChatClient request context (injected by AgentClientFactory) or falls back to AgentContextHolder.
     * 2. Populates MDC with agentId and LLM_RPC_START phase.
     * 3. Records start time, delegates to the next advisor in the chain.
     * 4. On success: extracts token usage from response metadata, logs LLM_RPC_END with latency and token counts.
     * 5. On failure: logs LLM_RPC_ERROR with error class and message (never the raw prompt).
     * 6. Always clears the phase MDC key in finally to prevent stale phase leaking into subsequent log lines.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            String agentId = resolveAgentId(request);

            AgentMdcFilter.setAgentId(agentId);
            AgentMdcFilter.setPhase("LLM_RPC_START");
            AgentMdcFilter.populateMdc();

            log.info("LLM synchronous call initiated for agent '{}'", agentId);
            publishEvent(AgentRunEventType.LLM_REQUEST, buildRequestPayload(agentId, request, "call"));

            long startNanos = System.nanoTime();
            try {
                ChatClientResponse response = chain.nextCall(request);
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

                AgentMdcFilter.setPhase("LLM_RPC_END");
                logCompletionMetrics(agentId, latencyMs, response);
                accumulateTelemetry(response, request);
                publishEvent(AgentRunEventType.LLM_RESPONSE,
                        buildResponsePayload(agentId, "call", "ok", latencyMs, response, null, null));

                return response;
            } catch (Exception e) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                AgentMdcFilter.setPhase("LLM_RPC_ERROR");
                log.error("LLM call failed for agent '{}' after {}ms: {} - {}",
                        agentId, latencyMs, e.getClass().getSimpleName(), e.getMessage());
                publishEvent(AgentRunEventType.LLM_RESPONSE,
                        buildResponsePayload(agentId, "call", "error", latencyMs, null,
                                e.getClass().getSimpleName(), e.getMessage()));
                throw e;
            } finally {
                MDC.remove(AgentMdcFilter.MDC_PHASE);
            }
        });
    }

    /**
     * @summary Intercepts streaming ChatClient calls to emit structured phase logs for stream lifecycle events.
     * @logic
     * 1. Populates MDC with agentId and LLM_STREAM_START phase at subscription time.
     * 2. Retains the last chunk that carries a token-usage block (streaming providers report usage only on a
     *    late/terminal chunk, usually cumulatively) so completion can report real token counts.
     * 3. Wraps the downstream Flux to log LLM_STREAM_END on completion (with elapsed time, token counts, and
     *    run-scoped telemetry accumulation) and LLM_STREAM_ERROR on failure.
     * 4. Cleans up the phase MDC key on stream termination (complete or error) to prevent leaks.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String agentId = resolveAgentId(request);

        return Flux.defer(() -> {
            AgentMdcFilter.setAgentId(agentId);
            AgentMdcFilter.setPhase("LLM_STREAM_START");
            AgentMdcFilter.populateMdc();

            log.info("LLM stream initiated for agent '{}'", agentId);
            publishEvent(AgentRunEventType.LLM_REQUEST, buildRequestPayload(agentId, request, "stream"));

            long startNanos = System.nanoTime();
            // Streaming providers surface token usage only on a late chunk (typically the terminal one), often
            // cumulatively. Retain the last chunk that carries a usage block so completion reports real token
            // counts. Without this, the LLM_RESPONSE event, the RunTelemetryAccumulator, and the persisted
            // AgentRun row all recorded zero tokens for every streamed turn — the sync path passed the response
            // here but the stream path passed null.
            AtomicReference<ChatClientResponse> lastWithUsage = new AtomicReference<>();

            return chain.nextStream(request)
                    .doOnNext(chunk -> {
                        if (hasUsage(chunk)) lastWithUsage.set(chunk);
                    })
                    .doOnComplete(() -> {
                        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                        AgentMdcFilter.setPhase("LLM_STREAM_END");
                        ChatClientResponse usageResponse = lastWithUsage.get();
                        logCompletionMetrics(agentId, latencyMs, usageResponse);
                        accumulateTelemetry(usageResponse, request);
                        publishEvent(AgentRunEventType.LLM_RESPONSE,
                                buildResponsePayload(agentId, "stream", "ok", latencyMs, usageResponse, null, null));
                        MDC.remove(AgentMdcFilter.MDC_PHASE);
                    })
                    .doOnError(e -> {
                        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                        AgentMdcFilter.setPhase("LLM_STREAM_ERROR");
                        log.error("LLM stream failed for agent '{}' after {}ms: {} - {}",
                                agentId, latencyMs, e.getClass().getSimpleName(), e.getMessage());
                        publishEvent(AgentRunEventType.LLM_RESPONSE,
                                buildResponsePayload(agentId, "stream", "error", latencyMs, null,
                                        e.getClass().getSimpleName(), e.getMessage()));
                        MDC.remove(AgentMdcFilter.MDC_PHASE);
                    });
        });
    }

    /** True when the response carries a token-usage block with at least one positive count. Used to pick the
     *  streaming chunk that holds the real (usually cumulative) usage reported near end-of-stream. The positive
     *  check matters: Spring AI defaults missing metadata to a zero {@code EmptyUsage} (never null), and some
     *  providers emit a content-only chunk AFTER the usage chunk — a bare non-null check would let that trailing
     *  zero overwrite the real totals. */
    private boolean hasUsage(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getMetadata() == null) {
            return false;
        }
        Usage usage = response.chatResponse().getMetadata().getUsage();
        if (usage == null) return false;
        return positive(usage.getPromptTokens()) || positive(usage.getCompletionTokens())
                || positive(usage.getTotalTokens());
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    /**
     * @summary Logs completion metrics including latency and token usage extracted from the ChatClientResponse.
     * @logic Safely navigates the response metadata chain to extract prompt/completion/total token counts.
     *        Logs at INFO level with structured fields for downstream JSON parsing.
     */
    private void logCompletionMetrics(String agentId, long latencyMs, ChatClientResponse response) {
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;

        if (response != null && response.chatResponse() != null
                && response.chatResponse().getMetadata() != null
                && response.chatResponse().getMetadata().getUsage() != null) {
            Usage usage = response.chatResponse().getMetadata().getUsage();
            // Spring AI Usage returns boxed Integer; null-check before unbox or NPE
            // surfaces as the LLM call's outcome. Mirror accumulateTelemetry's pattern.
            Integer pt = usage.getPromptTokens();
            Integer ct = usage.getCompletionTokens();
            Integer tt = usage.getTotalTokens();
            if (pt != null) promptTokens = pt;
            if (ct != null) completionTokens = ct;
            if (tt != null) totalTokens = tt;
        }

        log.info("LLM call completed for agent '{}' | latencyMs={} | promptTokens={} | completionTokens={} | totalTokens={}",
                agentId, latencyMs, promptTokens, completionTokens, totalTokens);
    }

    /**
     * @summary Accumulates per-LLM-call token + model telemetry into the run-scoped
     *          {@link RunTelemetryAccumulator}, which {@code AgentRunFinalizer} flushes
     *          onto {@code AgentRun} at run exit (§5.13).
     * @logic Best-effort, isolated from the advisor chain — a failure here never propagates.
     *        Cost accounting is owned by {@code GenAiMetricsAdvisor}; this method stays limited
     *        to token counts, call count, and model name (set-once) to avoid duplicate writes.
     */
    private void accumulateTelemetry(ChatClientResponse response, ChatClientRequest request) {
        RunTelemetryAccumulator acc = AgentContextHolder.getTelemetry();
        if (acc == null) return;
        try {
            acc.incrementLlmCalls();

            if (response != null && response.chatResponse() != null
                    && response.chatResponse().getMetadata() != null) {
                String model = response.chatResponse().getMetadata().getModel();
                if (model != null) acc.setModelIfAbsent(model);

                Usage usage = response.chatResponse().getMetadata().getUsage();
                if (usage != null) {
                    Integer pt = usage.getPromptTokens();
                    Integer ct = usage.getCompletionTokens();
                    if (pt != null) acc.addInputTokens(pt.longValue());
                    if (ct != null) acc.addOutputTokens(ct.longValue());
                }
            }

            if (acc.getModel() == null) {
                String reqModel = resolveModel(request);
                if (reqModel != null) acc.setModelIfAbsent(reqModel);
            }
        } catch (RuntimeException ex) {
            log.debug("RunTelemetryAccumulator write skipped: {}", ex.getMessage());
        }
    }

    /**
     * @summary Resolves the active agent ID from the request context or falls back to "unknown".
     * @logic Checks the ChatClientRequest context map for an "agentId" key (set by AgentClientFactory),
     *        then falls back to AgentContextHolder ScopedValue, and finally to "unknown".
     */
    private String resolveAgentId(ChatClientRequest request) {
        if (request.context() != null && request.context().containsKey("agentId")) {
            return String.valueOf(request.context().get("agentId"));
        }
        String scopedRunId = AgentContextHolder.getCurrentRunId();
        return scopedRunId != null ? scopedRunId : "unknown";
    }

    private Map<String, Object> buildRequestPayload(String agentId, ChatClientRequest request, String mode) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentId", agentId);
        String agentName = AgentContextHolder.getAgentName();
        if (agentName != null) payload.put("agentName", agentName);
        payload.put("mode", mode);
        String model = resolveModel(request);
        if (model != null) payload.put("model", model);
        int promptLength = resolvePromptLength(request);
        if (promptLength >= 0) payload.put("promptLength", promptLength);
        return payload;
    }

    private Map<String, Object> buildResponsePayload(String agentId, String mode, String status, long latencyMs,
                                                    ChatClientResponse response, String errorClass, String errorMessage) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentId", agentId);
        String agentName = AgentContextHolder.getAgentName();
        if (agentName != null) payload.put("agentName", agentName);
        payload.put("mode", mode);
        payload.put("status", status);
        payload.put("latencyMs", latencyMs);

        if (response != null && response.chatResponse() != null && response.chatResponse().getMetadata() != null) {
            String model = response.chatResponse().getMetadata().getModel();
            if (model != null) payload.put("model", model);
            Usage usage = response.chatResponse().getMetadata().getUsage();
            if (usage != null) {
                Integer pt = usage.getPromptTokens();
                Integer ct = usage.getCompletionTokens();
                Integer tt = usage.getTotalTokens();
                if (pt != null) payload.put("promptTokens", pt.longValue());
                if (ct != null) payload.put("completionTokens", ct.longValue());
                if (tt != null) payload.put("totalTokens", tt.longValue());
            }
        }

        if (errorClass != null) payload.put("errorClass", errorClass);
        if (errorMessage != null) payload.put("errorMessage", errorMessage);
        return payload;
    }

    private String resolveModel(ChatClientRequest request) {
        try {
            var options = request.prompt() != null ? request.prompt().getOptions() : null;
            if (options instanceof org.springframework.ai.chat.prompt.ChatOptions chatOptions) {
                String model = chatOptions.getModel();
                if (model != null && !model.isBlank()) return model;
            }
        } catch (Exception ignored) { /* not all prompts expose ChatOptions */ }
        return null;
    }

    private int resolvePromptLength(ChatClientRequest request) {
        try {
            if (request.prompt() != null && request.prompt().getContents() != null) {
                return request.prompt().getContents().length();
            }
        } catch (Exception ignored) { /* ChatClientRequest may differ across versions */ }
        return -1;
    }

    private void publishEvent(AgentRunEventType type, Map<String, Object> payload) {
        if (eventBus == null) return;
        String runId = AgentContextHolder.getCurrentRunId();
        if (runId == null) return; // LLM call outside a tracked run — skip timeline event
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
            // R-18: isolation — event publication must never break the LLM chain
            log.warn("Failed to publish {} event runId={}", type, runId, ex);
        }
    }
}
