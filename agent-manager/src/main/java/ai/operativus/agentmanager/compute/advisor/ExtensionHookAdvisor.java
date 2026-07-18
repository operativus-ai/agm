package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import ai.operativus.agentmanager.core.model.MetricConstants;
import ai.operativus.agentmanager.core.model.enums.HookPhase;
import ai.operativus.agentmanager.core.spi.AgentHookExtension;
import ai.operativus.agentmanager.core.spi.OutputPiiScrubber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Domain Responsibility: Executes opt-in Pre-Execution and Post-Execution extension hooks
 * (REST Webhooks and Java SPI plugins) as a Spring AI Advisor. By implementing both
 * CallAdvisor and StreamAdvisor, hook execution operates symmetrically across blocking
 * run() and reactive stream() paths without code duplication.
 *
 * @architecture This advisor is NOT a global singleton bean. It is instantiated per-agent
 *               inside AgentClientFactory.buildChatClient(), receiving that agent's specific
 *               preHooks and postHooks lists. Only hooks whose IDs appear in those lists
 *               are executed (Opt-In pattern).
 * State: Stateless (instantiated per ChatClient build)
 */
public class ExtensionHookAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ExtensionHookAdvisor.class);
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(5);
    private static final String HOOK_HEADER_PHASE = "X-Hook-Phase";
    private static final String HOOK_HEADER_ID = "X-Hook-Id";

    private final List<String> preHookIds;
    private final List<String> postHookIds;
    private final ExtensionRegistrationRepository extensionRepository;
    private final WebClient webClient;
    private final OutputPiiScrubber outputScrubber;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer, tag {@code advisor=extension_hook}. */
    private final Timer durationTimer;

    /**
     * @summary Constructs an ExtensionHookAdvisor scoped to a specific agent's hook assignments.
     * @param preHookIds The list of extension IDs to execute before the LLM call.
     * @param postHookIds The list of extension IDs to execute after the LLM call.
     * @param extensionRepository Repository for resolving extension metadata (URL, type).
     * @param webClient Reactive HTTP client for dispatching webhook payloads.
     * @param outputScrubber Scrubs PII from the LLM reply BEFORE post-hook dispatch. Closes
     *                       audit F5 (this advisor at order 15 unwinds before the order-10
     *                       PII guard, so without an explicit scrub here a post-hook receives
     *                       raw output). Use {@link OutputPiiScrubber#NO_OP} in unit tests
     *                       that don't exercise PII.
     * @param meterRegistry Micrometer registry for the advisor-duration timer.
     */
    public ExtensionHookAdvisor(List<String> preHookIds,
                                List<String> postHookIds,
                                ExtensionRegistrationRepository extensionRepository,
                                WebClient webClient,
                                OutputPiiScrubber outputScrubber,
                                MeterRegistry meterRegistry) {
        this.preHookIds = preHookIds != null ? preHookIds : Collections.emptyList();
        this.postHookIds = postHookIds != null ? postHookIds : Collections.emptyList();
        this.extensionRepository = extensionRepository;
        this.webClient = webClient;
        this.outputScrubber = outputScrubber != null ? outputScrubber : OutputPiiScrubber.NO_OP;
        this.durationTimer = Timer.builder(MetricConstants.ADVISOR_DURATION_MS)
                .tag("advisor", "extension_hook").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "ExtensionHookAdvisor";
    }

    /**
     * @summary Order 15 — runs after PII redaction (PIIAnonymizationAdvisor at order 10).
     * @logic Per audit F12: this advisor previously ran at order -10, before the PII redactor,
     *        so {@code extractUserText(request)} returned raw user prompts that flowed directly
     *        into outbound webhook POST bodies. Org admins configuring a webhook URL would
     *        receive every user prompt for every agent that opted in — including PII the org's
     *        own policies would otherwise redact.
     *
     *        Moving past the PII boundary makes pre-hook payloads the redacted form. Pre-hook
     *        enrichment based on raw PII (e.g., looking up a customer by an email mentioned in
     *        the prompt) is intentionally disabled — that capability was a security hole, not
     *        a feature. If a future use case requires raw-payload pre-hooks, add a per-extension
     *        opt-out flag and dual-instance wiring in AgentClientFactory; do not lower this order.
     */
    @Override
    public int getOrder() {
        return 15;
    }

    /**
     * @summary Intercepts synchronous chat client calls to execute pre/post extension hooks.
     * @logic
     * 1. Iterates over assigned preHookIds, dispatching each to its webhook URL or SPI handler.
     * 2. Delegates to the next advisor in the chain for LLM execution.
     * 3. Iterates over assigned postHookIds, dispatching each with the LLM response.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            if (preHookIds.isEmpty() && postHookIds.isEmpty()) {
                return chain.nextCall(request);
            }

            for (String hookId : preHookIds) {
                try {
                    dispatchHook(hookId, HookPhase.PRE, extractUserText(request), Map.of());
                } catch (Exception e) {
                    log.warn("Pre-execution hook {} failed (fail-safe skip): {}", hookId, e.getMessage());
                }
            }

            ChatClientResponse response = chain.nextCall(request);

            // Scrub the LLM reply BEFORE dispatching post-hooks. This advisor at order 15
            // unwinds before PIIAnonymizationAdvisor at order 10 runs its response-side
            // redactResponse, so without this scrub the webhook payload would carry raw
            // LLM-echoed PII (audit F5 post-hook gap).
            String outputText = outputScrubber.scrub(extractOutputText(response), request);
            for (String hookId : postHookIds) {
                try {
                    dispatchHook(hookId, HookPhase.POST, outputText, Map.of());
                } catch (Exception e) {
                    log.warn("Post-execution hook {} failed (fail-safe skip): {}", hookId, e.getMessage());
                }
            }

            return response;
        });
    }

    /**
     * @summary Intercepts streaming chat client calls to execute pre-execution hooks before the LLM stream begins.
     * @logic
     * 1. Dispatches all pre-hooks synchronously before subscribing to the LLM reactive stream.
     * 2. Accumulates streaming chunks and dispatches post-hooks on stream completion.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (preHookIds.isEmpty() && postHookIds.isEmpty()) {
            return chain.nextStream(request);
        }

        // --- Pre-Execution Hooks (synchronous, before stream starts) ---
        for (String hookId : preHookIds) {
            try {
                dispatchHook(hookId, HookPhase.PRE, extractUserText(request), Map.of());
            } catch (Exception e) {
                log.warn("Pre-execution hook {} failed during stream (fail-safe skip): {}", hookId, e.getMessage());
            }
        }

        // --- Stream with Post-Execution Hooks on completion ---
        if (postHookIds.isEmpty()) {
            return chain.nextStream(request);
        }

        StringBuilder fullResponseBuffer = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(resp -> {
                    if (resp.chatResponse() != null
                            && resp.chatResponse().getResult() != null
                            && resp.chatResponse().getResult().getOutput() != null) {
                        String chunk = resp.chatResponse().getResult().getOutput().getText();
                        if (chunk != null) {
                            fullResponseBuffer.append(chunk);
                        }
                    }
                })
                .doFinally(signalType -> {
                    // Fire post-hooks on ANY terminal signal (complete, error, cancel) so
                    // ops dashboards counting hook invocations see a record of every run
                    // regardless of outcome. The buffered text reflects whatever chunks
                    // arrived before the terminal signal — empty on early cancel, partial
                    // on mid-flight error. Same audit F5 motivation as the adviseCall branch:
                    // scrub before dispatch.
                    String fullText = outputScrubber.scrub(fullResponseBuffer.toString(), request);
                    for (String hookId : postHookIds) {
                        try {
                            dispatchHook(hookId, HookPhase.POST, fullText, Map.of());
                        } catch (Exception e) {
                            log.warn("Post-execution hook {} failed during stream terminal signal {} (fail-safe skip): {}",
                                    hookId, signalType, e.getMessage());
                        }
                    }
                });
    }

    /**
     * @summary Dispatches a hook invocation to the appropriate handler based on extension type.
     * @logic
     * 1. Resolves the hookId from the extensions database table FIRST. Matches
     *    {@code ExtensionController.getExtensions} list precedence (DB wins over SPI) so an
     *    operator who sees a row in {@code GET /api/v1/extensions} gets the row they see.
     *    F3 fix — earlier the dispatcher checked SPI first, producing a silent divergence
     *    between the list view and the actual dispatch path.
     * 2. If the DB row is inactive or has an unknown type, the dispatch is SKIPPED (no
     *    SPI fallback) — the operator deliberately registered the DB row.
     * 3. If NO DB row exists for the id, fall through to the {@code ServiceLoader}-discovered
     *    {@link AgentHookExtension} SPI.
     * 4. WEBHOOK dispatches an HTTP POST; MCP is a no-op here (handled by {@code McpConnectionPool}).
     */
    private void dispatchHook(String hookId, HookPhase phase, String payload, Map<String, Object> context) {
        // --- Resolve from Database FIRST (F3: matches getExtensions list precedence) ---
        ExtensionRegistrationEntity extension = extensionRepository.findById(hookId).orElse(null);
        if (extension != null) {
            if (!Boolean.TRUE.equals(extension.getActive())) {
                log.warn("Extension hook {} is registered in DB but inactive; skipping (no SPI fallback when a DB row exists).", hookId);
                return;
            }
            if ("WEBHOOK".equalsIgnoreCase(extension.getType())) {
                dispatchWebhook(extension, phase, hookId, payload);
                return;
            }
            if ("MCP".equalsIgnoreCase(extension.getType())) {
                // MCP tools are mounted via McpConnectionPool and resolved in AgentClientFactory.
                // They are NOT dispatched as hooks — this is a configuration mismatch by the admin.
                log.debug("Skipping MCP extension {} in hook advisor; MCP tools are resolved via McpConnectionPool.", hookId);
                return;
            }
            log.warn("Extension hook {} has unknown DB type '{}'; skipping (no SPI fallback when a DB row exists).",
                    hookId, extension.getType());
            return;
        }

        // --- SPI fallback only when DB has no row for this id ---
        for (AgentHookExtension spiHook : ServiceLoader.load(AgentHookExtension.class)) {
            if (hookId.equals(spiHook.getExtensionId())) {
                log.debug("Dispatching SPI {} hook: {}", phase, hookId);
                if (phase == HookPhase.PRE) {
                    spiHook.beforeExecution(hookId, payload, context);
                } else {
                    spiHook.afterExecution(hookId, payload, context);
                }
                return;
            }
        }

        log.warn("Extension hook {} not found in DB or SPI; skipping.", hookId);
    }

    /**
     * @summary Dispatches a single WEBHOOK extension as an HTTP POST. Extracted from
     *          {@link #dispatchHook} so the DB-first precedence flow reads cleanly.
     */
    private void dispatchWebhook(ExtensionRegistrationEntity extension, HookPhase phase, String hookId, String payload) {
        log.debug("Dispatching WEBHOOK {} hook to {}: {}", phase, extension.getUrl(), hookId);
        try {
            webClient.post()
                    .uri(extension.getUrl())
                    .header("Content-Type", "application/json")
                    .header(HOOK_HEADER_PHASE, phase.name())
                    .header(HOOK_HEADER_ID, hookId)
                    .header("User-Agent", "AgentManager-ExtensionHookAdvisor")
                    .bodyValue(Map.of("phase", phase.name(), "hookId", hookId, "payload", payload))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(WEBHOOK_TIMEOUT)
                    .block(); // Blocking is safe on Virtual Threads
        } catch (Exception e) {
            log.warn("Webhook dispatch to {} failed: {}", extension.getUrl(), e.getMessage());
        }
    }

    /**
     * @summary Extracts the user's text content from a ChatClientRequest.
     */
    private String extractUserText(ChatClientRequest request) {
        if (request.prompt() != null && request.prompt().getContents() != null) {
            return request.prompt().getContents();
        }
        return "";
    }

    /**
     * @summary Extracts the assistant's text output from a ChatClientResponse.
     */
    private String extractOutputText(ChatClientResponse response) {
        if (response.chatResponse() != null
                && response.chatResponse().getResult() != null
                && response.chatResponse().getResult().getOutput() != null) {
            return response.chatResponse().getResult().getOutput().getText();
        }
        return "";
    }
}
