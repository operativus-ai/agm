package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.EventType;
import ai.operativus.agentmanager.core.model.RunOptions;
import ai.operativus.agentmanager.core.model.ToolCallDTO;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.RunOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.Callable;

/**
 * Domain Responsibility: Constructs and manages the reactive SSE stream for single-agent LLM executions.
 *
 * <p>F22 — Reactor operators in this class run on {@code boundedElastic} (via
 * {@code subscribeOn}) which is a fresh thread that does NOT inherit JDK 21 ScopedValues
 * from the caller. Without explicit re-binding, the advisor chain (RAG, PII redaction,
 * cultural memory, encryption gate, telemetry) reads {@code AgentContextHolder.getOrgId()}
 * etc. as null/defaults and fails tenant isolation. Each operator lambda below re-binds
 * via {@link #withBindings} using values captured on the caller thread.</p>
 *
 * State: Stateless
 */
@Service
public class AgentStreamManager {

    /**
     * Captured ScopedValue bindings + ancillary context the advisor chain reads. The
     * caller (AgentService.stream) builds this on its own thread, where AgentContextHolder
     * is bound; AgentStreamManager re-binds these inside each Reactor operator's lambda.
     */
    public record StreamScopeBindings(
            String orgId,
            String userId,
            String sessionId,
            String agentId,
            String agentName,
            String currentRunId,
            int orchestrationDepth,
            RunTelemetryAccumulator telemetry,
            List<ToolCallDTO> toolTraces,
            Set<String> approvedTools,
            String[] workflowRunId,
            List<String> allowedKnowledgeBaseIds,
            boolean requiresEncryption
    ) {}

    private static final Logger log = LoggerFactory.getLogger(AgentStreamManager.class);

    private final AgentClientFactory agentClientFactory;
    private final RunOperations runRepository;
    private final ReflectionService reflectionService;
    private final AgentRunFinalizer agentRunFinalizer;
    private final RunExecutionManager runExecutionManager;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public AgentStreamManager(AgentClientFactory agentClientFactory,
                               RunOperations runRepository,
                               ReflectionService reflectionService,
                               AgentRunFinalizer agentRunFinalizer,
                               RunExecutionManager runExecutionManager,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.agentClientFactory = agentClientFactory;
        this.runRepository = runRepository;
        this.reflectionService = reflectionService;
        this.agentRunFinalizer = agentRunFinalizer;
        this.runExecutionManager = runExecutionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * @summary Builds the terminal {@code METRICS} usage-summary frame from the run-scoped
     *     {@link RunTelemetryAccumulator}. Payload is a compact JSON object with cumulative token
     *     counts, LLM call count, model, and USD cost — the single authoritative total for the run.
     *     This is the only streamed frame that carries USD cost.
     */
    private AgentStreamEvent buildUsageSummaryEvent(RunTelemetryAccumulator acc) {
        try {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            long input = acc.getTotalInputTokens();
            long output = acc.getTotalOutputTokens();
            long reasoning = acc.getTotalReasoningTokens();
            m.put("inputTokens", input);
            m.put("outputTokens", output);
            if (reasoning > 0) m.put("reasoningTokens", reasoning);
            m.put("totalTokens", input + output + reasoning);
            m.put("llmCalls", acc.getLlmCallCount());
            if (acc.getModel() != null) m.put("model", acc.getModel());
            double cost = acc.getTotalCostUsd();
            if (cost > 0) m.put("costUsd", cost);
            return new AgentStreamEvent(EventType.METRICS, objectMapper.writeValueAsString(m),
                    System.currentTimeMillis());
        } catch (Exception ex) {
            return new AgentStreamEvent(EventType.METRICS, "{}", System.currentTimeMillis());
        }
    }

    /**
     * @summary Constructs the reactive Flux for a single-agent streaming execution.
     * @logic Builds a ChatClient, sets up content/reasoning delta events, handles followup generation,
     *        and persists the run record on stream completion or failure. Each Reactor operator's
     *        lambda re-binds {@code bindings} via {@link #withBindings} so the advisor chain on
     *        {@code boundedElastic} sees the correct tenant/run context.
     */
    public Flux<AgentStreamEvent> stream(AgentDefinition def, String agentId, String userInput,
                                         List<Media> media, String effectiveSessionId,
                                         String userId, String orgId, Boolean generateFollowups,
                                         RunOptions options, AgentRun runRecord,
                                         StreamScopeBindings bindings) {
        log.debug("Initiating LLM stream for agent '{}' with model '{}'", agentId, def.modelId());

        StringBuilder fullResponse = new StringBuilder();
        Flux<AgentStreamEvent> start = Flux.just(new AgentStreamEvent(EventType.START, "", System.currentTimeMillis()));

        // Build the ChatClient inside the bindings scope so any advisor wiring (e.g. tenant-scoped
        // RAG/cultural memory advisors) sees the correct orgId at construction time.
        ChatClient client = withBindings(bindings, () ->
                agentClientFactory.buildChatClient(def, effectiveSessionId, userId, orgId, options));

        List<String> fallbackModelIds = def.fallbackModelIds() != null ? def.fallbackModelIds() : Collections.emptyList();

        Flux<AgentStreamEvent> content = buildClientContentFlux(client, bindings, userInput, media, fullResponse)
                .onErrorResume(e -> withBindings(bindings, () -> {
                    log.error("Stream processing failed due to exception", e);
                    String errorDetail = e.getClass().getName() + ": " + e.getMessage();
                    if (e.getCause() != null) {
                        errorDetail += " | Cause: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage();
                    }

                    if (AgentErrorClassifier.isQuotaOrRateLimitError(e) && !fallbackModelIds.isEmpty()) {
                        // gap #8 \u2014 walk the entire fallback chain on quota/rate-limit, not just .get(0).
                        // tryStreamFallbackChain returns null when build fails for entry 0 (caller falls
                        // through to the legacy error path), otherwise a Flux that recursively chains
                        // through the list.
                        Flux<AgentStreamEvent> chained = tryStreamFallbackChain(
                                fallbackModelIds, 0, def, effectiveSessionId, userId, orgId, options,
                                bindings, userInput, media, fullResponse, runRecord);
                        if (chained != null) {
                            return chained;
                        }
                    }

                    String errorOutput = "Error: " + errorDetail;
                    // Keep local runRecord state in sync so doOnComplete's guard short-circuits.
                    runRecord.setStatus(RunStatus.FAILED);
                    runRecord.setOutput(errorOutput);
                    agentRunFinalizer.finalizeRun(runRecord.getId(), RunStatus.FAILED,
                            errorOutput, null, bindings.telemetry());

                    if (AgentErrorClassifier.isContextLimitError(e)) {
                        log.warn("[stream] Context window limit hit for agent {} on model {}", agentId, def.modelId());
                        return Flux.just(new AgentStreamEvent(EventType.ERROR,
                                "\u26a0\ufe0f This conversation has exceeded the model's context window. Please start a new session to continue, or ask me to summarize the conversation first.",
                                System.currentTimeMillis()));
                    }

                    if (AgentErrorClassifier.isQuotaOrRateLimitError(e)) {
                        log.warn("[stream] Quota or rate limit exceeded for agent {} on model {} (no fallback available)", agentId, def.modelId());
                        return Flux.just(new AgentStreamEvent(EventType.ERROR,
                                "\u26a0\ufe0f The AI provider's rate limit or token quota has been exceeded. Please wait a moment and try again, or contact your administrator if this persists.",
                                System.currentTimeMillis()));
                    }

                    if (e.getCause() instanceof java.io.InterruptedIOException ||
                       (e.getMessage() != null && e.getMessage().contains("Failed to read next JSON object"))) {
                        return Flux.empty();
                    }
                    return Flux.just(new AgentStreamEvent(EventType.ERROR, "Stream Error: " + errorDetail, System.currentTimeMillis()));
                }));

        Flux<AgentStreamEvent> contentDone = Flux.just(new AgentStreamEvent(EventType.CONTENT_DONE, "", System.currentTimeMillis()));

        Flux<AgentStreamEvent> followups = Flux.empty();
        if (Boolean.TRUE.equals(generateFollowups)) {
            followups = Flux.defer(() -> withBindings(bindings, () -> {
                try {
                    String fpPrompt = "Based on the conversation so far, generate 3 highly relevant and concise followup questions the user could ask next. Return EXACTLY a complete JSON array of strings e.g. [\"question 1?\", \"question 2?\", \"question 3?\"].";
                    String fpResponse = client.prompt()
                            .user("Analyze the conversation history and suggest followups.")
                            .system(fpPrompt)
                            .call()
                            .content();
                    return Flux.just(new AgentStreamEvent(EventType.FOLLOWUP_SUGGESTION, fpResponse, System.currentTimeMillis()));
                } catch (Exception e) {
                    log.warn("Failed to generate predictive followups in streaming response: {}", e.getMessage());
                    return Flux.empty();
                }
            })).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        }

        Flux<AgentStreamEvent> stop = Flux.just(new AgentStreamEvent(EventType.STOP, "", System.currentTimeMillis()))
                .doOnComplete(() -> withBindingsRun(bindings, () -> {
                    if (runRecord.getStatus() != RunStatus.FAILED) {
                        // Mark local state COMPLETED so a downstream doFinally CANCEL
                        // signal short-circuits — the row has already terminated cleanly.
                        runRecord.setStatus(RunStatus.COMPLETED);
                        runRecord.setOutput(fullResponse.toString());
                        agentRunFinalizer.finalizeRun(runRecord.getId(), RunStatus.COMPLETED,
                                fullResponse.toString(), null, bindings.telemetry());
                        reflectionService.reflectOnRun(userInput, fullResponse.toString(), userId, runRecord.getId(), agentId, effectiveSessionId);
                    }
                }));

        // Register a cancellation Mono keyed by this run's id. The Mono completes when
        // a sibling caller invokes AgentOperations.cancelRun(runId) → RunExecutionManager.cancel,
        // which emits on the per-runId sink. We translate that signal into a terminal
        // CANCELLED AgentStreamEvent so SSE clients can distinguish a user-initiated
        // cancel from a network drop or natural completion. Pre-fix the stream was cut
        // with takeUntilOther which closed the connection silently — clients saw only
        // the initial START event and no terminal marker.
        //
        // Implementation note: the flag-then-concatWith pattern keeps both happy-path
        // (natural completion) and cancel-path completion deterministic. A naive
        // Flux.merge of the cancelSignal Mono would hang on the happy path because the
        // cancelSignal never completes when no cancel arrives — the merge would wait
        // forever for both sources.
        reactor.core.publisher.Mono<Void> cancelSignal =
                runExecutionManager.registerStreamingCancellationSignal(runRecord.getId());
        java.util.concurrent.atomic.AtomicBoolean cancelledFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        reactor.core.publisher.Mono<Void> cancelWithFlag = cancelSignal
                .doOnSuccess(ignored -> cancelledFlag.set(true));
        reactor.core.publisher.Mono<AgentStreamEvent> maybeCancelTerminal =
                reactor.core.publisher.Mono.fromCallable(() -> cancelledFlag.get()
                        ? new AgentStreamEvent(EventType.CANCELLED,
                                "Run cancelled by client",
                                System.currentTimeMillis())
                        : null);

        // The chat stream carries ONLY the assistant's content (token frames + terminal markers).
        // Execution events (tool calls, delegation, handoff, LLM turns) are NOT merged in here —
        // they are persisted to agent_run_events by AgentRunEventBus and surfaced on the dedicated
        // per-agent / per-run event streams (GET /api/v1/agents/{id}/events, /api/v1/runs/{id}/events).
        // Keeping the two channels separate means chat shows the conversation and the event views
        // show the trace.
        // Terminal usage summary (EventType.METRICS) emitted just before STOP: one authoritative
        // per-run token+cost total read from the run-scoped accumulator. It is the only streamed
        // frame carrying USD cost; suppressed when no usage was captured (provider didn't report it,
        // or no LLM call ran) so we never emit an all-zero frame.
        Flux<AgentStreamEvent> summary = Flux.defer(() -> {
            RunTelemetryAccumulator acc = bindings.telemetry();
            if (acc == null || acc.getTotalInputTokens() + acc.getTotalOutputTokens() <= 0) {
                return Flux.empty();
            }
            return Flux.just(buildUsageSummaryEvent(acc));
        });
        Flux<AgentStreamEvent> mainSeq = Flux.concat(start, content, contentDone, followups, summary, stop);

        return mainSeq
                .takeUntilOther(cancelWithFlag)
                .concatWith(maybeCancelTerminal)
                .doFinally(signalType -> withBindingsRun(bindings, () -> {
                    runExecutionManager.unregisterStreamingCancellationSignal(runRecord.getId());
                    // F6: covers client disconnect / subscription cancel mid-stream — the
                    // success and error paths above already wrote terminal state, so the guard
                    // ensures we only finalize when the row is genuinely stuck non-terminal.
                    if (signalType == reactor.core.publisher.SignalType.CANCEL
                            && runRecord.getStatus() != RunStatus.COMPLETED
                            && runRecord.getStatus() != RunStatus.FAILED) {
                        log.info("Stream cancelled by client; finalizing run {} as CANCELLED", runRecord.getId());
                        agentRunFinalizer.finalizeRun(runRecord.getId(), RunStatus.CANCELLED,
                                "Stream cancelled by client", null, bindings.telemetry());
                    }
                }));
    }


    /**
     * gap #8 — walks the per-agent fallback model chain on quota/rate-limit. Returns
     * a Flux that:
     * <ol>
     *   <li>tries chain[index] via {@link AgentClientFactory#buildChatClientForFallback},</li>
     *   <li>on quota/rate-limit on that fallback, recursively chains to chain[index+1],</li>
     *   <li>on non-quota error during a fallback attempt, terminates the chain and
     *       finalizes the run as FAILED with an error event,</li>
     *   <li>when the chain is exhausted, finalizes the run and emits the
     *       "both primary and fallback unavailable" message.</li>
     * </ol>
     * Returns {@code null} ONLY when building the index=0 fallback ChatClient itself
     * throws — caller falls through to the legacy error path in that edge case
     * (matches pre-PR behavior for build failures).
     */
    private Flux<AgentStreamEvent> tryStreamFallbackChain(List<String> chain, int index,
            AgentDefinition def, String sessionId, String userId, String orgId,
            ai.operativus.agentmanager.core.model.RunOptions options,
            StreamScopeBindings bindings, String userInput, List<Media> media,
            StringBuilder fullResponse,
            ai.operativus.agentmanager.core.entity.AgentRun runRecord) {
        if (index >= chain.size()) {
            // Exhausted — finalize and emit the unavailable message.
            return withBindings(bindings, () -> {
                String msg = "All fallback models exhausted for run " + runRecord.getId();
                log.warn("[stream] {}", msg);
                runRecord.setStatus(RunStatus.FAILED);
                runRecord.setOutput(msg);
                agentRunFinalizer.finalizeRun(runRecord.getId(), RunStatus.FAILED, msg, null, bindings.telemetry());
                return Flux.just(new AgentStreamEvent(EventType.ERROR,
                        "⚠️ Both primary and fallback AI models are unavailable. Please try again later.",
                        System.currentTimeMillis()));
            });
        }
        String fallbackModelId = chain.get(index);
        if (fallbackModelId == null || fallbackModelId.isBlank() || fallbackModelId.equals(def.modelId())) {
            return tryStreamFallbackChain(chain, index + 1, def, sessionId, userId, orgId, options,
                    bindings, userInput, media, fullResponse, runRecord);
        }
        ChatClient fallbackClient;
        try {
            fullResponse.setLength(0);
            log.warn("[stream] Trying fallback model '{}' (index {} of {})", fallbackModelId, index, chain.size());
            fallbackClient = agentClientFactory.buildChatClientForFallback(
                    def, sessionId, userId, orgId, options, fallbackModelId);
        } catch (Exception buildEx) {
            log.error("[stream] Failed to build fallback ChatClient for model '{}'", fallbackModelId, buildEx);
            if (index == 0) {
                return null; // caller's legacy path handles the build-fail-on-entry case
            }
            // Mid-chain build failure — treat as a per-entry skip and try the next.
            return tryStreamFallbackChain(chain, index + 1, def, sessionId, userId, orgId, options,
                    bindings, userInput, media, fullResponse, runRecord);
        }
        return buildClientContentFlux(fallbackClient, bindings, userInput, media, fullResponse)
                .onErrorResume(fe -> withBindings(bindings, () -> {
                    if (AgentErrorClassifier.isQuotaOrRateLimitError(fe)) {
                        log.warn("[stream] Fallback model '{}' also rate-limited; trying next in chain", fallbackModelId);
                        return tryStreamFallbackChain(chain, index + 1, def, sessionId, userId, orgId, options,
                                bindings, userInput, media, fullResponse, runRecord);
                    }
                    log.error("[stream] Fallback model '{}' failed with non-quota error; breaking chain", fallbackModelId, fe);
                    String fbError = "Error: " + fe.getClass().getName() + ": " + fe.getMessage();
                    runRecord.setStatus(RunStatus.FAILED);
                    runRecord.setOutput(fbError);
                    agentRunFinalizer.finalizeRun(runRecord.getId(), RunStatus.FAILED, fbError, null, bindings.telemetry());
                    return Flux.just(new AgentStreamEvent(EventType.ERROR,
                            "⚠️ Fallback AI model failed. Please try again later.",
                            System.currentTimeMillis()));
                }));
    }

    private Flux<AgentStreamEvent> buildClientContentFlux(ChatClient client, StreamScopeBindings bindings,
            String userInput, List<Media> media, StringBuilder fullResponse) {
        // F22-fix: Flux.defer(() -> withBindings(..., () -> lazyFlux)) exits the ScopedValue
        // scope before Spring AI subscribes and calls adviseStream() — so advisors (RAG,
        // PII, etc.) saw null orgId. Flux.create bridges the subscription inside
        // withBindingsRun so the advisor chain assembles while ScopedValues are bound.
        return Flux.<org.springframework.ai.chat.model.ChatResponse>create(emitter ->
                withBindingsRun(bindings, () -> {
                    reactor.core.Disposable subscription = client.prompt()
                            .user(u -> {
                                u.text(userInput);
                                if (media != null) u.media(media.toArray(new Media[0]));
                            })
                            .stream()
                            .chatResponse()
                            // Gemini streams a finish-reason-only chunk with no content as the last event.
                            // Spring AI calls Optional.get() unconditionally in responseCandidateToGeneration(),
                            // throwing NoSuchElementException. All real content has already arrived at that point,
                            // so completing with empty here is safe. Remove when upstream is fixed.
                            .onErrorResume(java.util.NoSuchElementException.class, ex -> {
                                log.debug("Skipping empty Gemini streaming chunk (spring-ai upstream bug): {}", ex.getMessage());
                                return reactor.core.publisher.Flux.empty();
                            })
                            .subscribe(emitter::next, emitter::error, emitter::complete);
                    // Propagate downstream cancellation (client disconnect → outer takeUntilOther/
                    // doFinally) into the inner LLM stream so it stops consuming/billing tokens once
                    // the client is gone. Flux.create discards the inner Disposable otherwise — the
                    // original Flux.defer propagated cancel natively; this restores that.
                    emitter.onCancel(subscription);
                }))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .concatMap(response -> withBindings(bindings, () -> {
                    List<AgentStreamEvent> events = new ArrayList<>();
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        org.springframework.ai.chat.messages.AssistantMessage msg = response.getResult().getOutput();

                        String reasoningDelta = null;
                        if (msg.getMetadata() != null && msg.getMetadata().containsKey("reasoning")) {
                            reasoningDelta = (String) msg.getMetadata().get("reasoning");
                        } else if (response.getResult().getMetadata() != null && response.getResult().getMetadata().get("reasoning") != null) {
                            reasoningDelta = (String) response.getResult().getMetadata().get("reasoning");
                        } else if (msg.getMetadata() != null && msg.getMetadata().containsKey("reasoningContent")) {
                            reasoningDelta = (String) msg.getMetadata().get("reasoningContent");
                        }

                        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                            events.add(new AgentStreamEvent(EventType.REASONING_DELTA, reasoningDelta, System.currentTimeMillis()));
                        }

                        String text = msg.getText();
                        if (text != null && !text.isEmpty()) {
                            fullResponse.append(text);
                            events.add(new AgentStreamEvent(EventType.CONTENT_DELTA, text, System.currentTimeMillis()));
                        }

                        if (msg.hasToolCalls()) {
                            for (var call : msg.getToolCalls()) {
                                events.add(new AgentStreamEvent(EventType.TOOL_START, "Executing Tool: " + call.name(), System.currentTimeMillis()));
                            }
                        }
                    }
                    return Flux.fromIterable(events);
                }));
    }

    /**
     * Re-binds the captured ScopedValues from {@code bindings} on the current thread, then runs
     * {@code body} inside that scope. Bridges {@code AgentContextHolder} ScopedValues into MDC at
     * scope entry and clears them at exit so structured logs carry runId/orgId/etc.
     *
     * <p>Used to wrap Reactor operator lambdas that execute on {@code boundedElastic} where
     * ScopedValues from the caller's thread are NOT visible. Without this, the advisor chain
     * reads {@code getOrgId()} as null and tenant-scoped advisors (RAG, cultural memory,
     * encryption gate) silently misbehave.</p>
     */
    private <T> T withBindings(StreamScopeBindings b, Callable<T> body) {
        try {
            return ScopedValue.where(AgentContextHolder.orgId, b.orgId() != null ? b.orgId() : "")
                    .where(AgentContextHolder.userId, b.userId() != null ? b.userId() : "")
                    .where(AgentContextHolder.sessionId, b.sessionId() != null ? b.sessionId() : "")
                    .where(AgentContextHolder.agentId, b.agentId() != null ? b.agentId() : "")
                    .where(AgentContextHolder.agentName, b.agentName() != null ? b.agentName() : "")
                    .where(AgentContextHolder.currentRunId, b.currentRunId() != null ? b.currentRunId() : "")
                    .where(AgentContextHolder.orchestrationDepth, b.orchestrationDepth())
                    .where(AgentContextHolder.telemetry, b.telemetry())
                    .where(AgentContextHolder.toolTraces, b.toolTraces())
                    .where(AgentContextHolder.approvedTools, b.approvedTools())
                    .where(AgentContextHolder.workflowRunId, b.workflowRunId())
                    .where(AgentContextHolder.allowedKnowledgeBaseIds, b.allowedKnowledgeBaseIds())
                    .where(AgentContextHolder.requiresEncryption, b.requiresEncryption())
                    .call(() -> {
                        AgentContextHolder.populateMdcFromScopedValues();
                        try {
                            return body.call();
                        } finally {
                            AgentContextHolder.clearMdcFromScopedValues();
                        }
                    });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Void-returning variant for {@code doOnComplete}/{@code doFinally} lambdas. */
    private void withBindingsRun(StreamScopeBindings b, Runnable body) {
        withBindings(b, () -> { body.run(); return null; });
    }
}
