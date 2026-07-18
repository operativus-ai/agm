package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.compute.security.DeterministicNEREngine;
import ai.operativus.agentmanager.compute.security.FormatPreservingEncryptionService;
import ai.operativus.agentmanager.compute.security.PiiAuditLogEntity;
import ai.operativus.agentmanager.compute.security.PiiAuditLogRepository;
import ai.operativus.agentmanager.compute.security.PiiPolicyEntity;
import ai.operativus.agentmanager.compute.security.PiiPolicyService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.MetricConstants;
import ai.operativus.agentmanager.core.spi.OutputPiiScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * Domain Responsibility: Scans outgoing LLM requests and redacts Personally Identifiable Information (PII)
 * before it leaves the application boundary. Uses the dynamic PII Policy Governance system to determine
 * which rules apply to the specific agent making the request, then delegates detection to the
 * {@link DeterministicNEREngine} and scrubbing to the {@link FormatPreservingEncryptionService}.
 *
 * <p>Each scrub event is recorded in the {@code pii_audit_log} table for NHI (Non-Human Identity)
 * compliance traceability without ever storing the actual PII.</p>
 *
 * State: Stateless
 */
@Component
public class PIIAnonymizationAdvisor implements CallAdvisor, StreamAdvisor, OutputPiiScrubber {

    private static final Logger log = LoggerFactory.getLogger(PIIAnonymizationAdvisor.class);

    /** Context key matching the convention used by AgentLoggingAdvisor for agent identification. */
    public static final String AGENT_ID_KEY = "agentId";
    /** Context key for session identification in audit logging. */
    public static final String SESSION_ID_KEY = "sessionId";

    private final PiiPolicyService policyService;
    private final DeterministicNEREngine nerEngine;
    private final FormatPreservingEncryptionService fpeService;
    private final PiiAuditLogRepository auditLogRepository;

    /** Counts PII scrub events — every distinct redaction inside a single request increments by 1.
     *  Distinct from {@code agm.security.pii.scanned} which counts requests, not events. */
    private final io.micrometer.core.instrument.Counter redactionEvents;
    /** Counts requests scanned for PII. {@code outcome=clean} when no policy matched anything;
     *  {@code outcome=redacted} when at least one event fired. */
    private final io.micrometer.core.instrument.Counter scannedClean;
    private final io.micrometer.core.instrument.Counter scannedRedacted;
    /** Per-advisor processing-time timer — supports the §2 advisor-chain decomposition gap.
     *  Wraps adviseCall to attribute LLM-round-trip latency to PII scanning specifically. */
    private final io.micrometer.core.instrument.Timer durationTimer;

    public PIIAnonymizationAdvisor(PiiPolicyService policyService,
                                   DeterministicNEREngine nerEngine,
                                   FormatPreservingEncryptionService fpeService,
                                   PiiAuditLogRepository auditLogRepository,
                                   io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.policyService = policyService;
        this.nerEngine = nerEngine;
        this.fpeService = fpeService;
        this.auditLogRepository = auditLogRepository;
        this.redactionEvents = io.micrometer.core.instrument.Counter.builder(MetricConstants.PII_REDACTION_EVENTS)
                .register(meterRegistry);
        this.scannedClean = io.micrometer.core.instrument.Counter.builder(MetricConstants.PII_SCANNED)
                .tag("outcome", "clean").register(meterRegistry);
        this.scannedRedacted = io.micrometer.core.instrument.Counter.builder(MetricConstants.PII_SCANNED)
                .tag("outcome", "redacted").register(meterRegistry);
        this.durationTimer = io.micrometer.core.instrument.Timer.builder(MetricConstants.ADVISOR_DURATION_MS)
                .tag("advisor", "pii_anonymization").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "PIIAnonymizationAdvisor";
    }

    @Override
    public int getOrder() {
        return 10; // Run after injection check
    }

    /**
     * @summary Intercepts synchronous chat client calls to redact PII from the user prompt.
     * @logic Extracts the agent ID from the advisor context, resolves the applicable PII policies,
     *        delegates scanning and scrubbing to the NER engine, and records each scrub event
     *        in the audit log.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            ChatClientRequest redactedRequest = redactRequest(request);
            ChatClientResponse response = chain.nextCall(redactedRequest);
            return redactResponse(response, redactedRequest);
        });
    }

    /**
     * @summary Intercepts streaming chat client calls to redact PII from the user prompt
     *          (request-side only).
     * @logic Redacts the request via {@link #redactRequest(ChatClientRequest)} so input PII
     *        never reaches the LLM, then delegates the response stream untouched.
     *
     *        <p><b>Output-side redaction is intentionally NOT applied here.</b> Audit F5
     *        documents this gap: TIER_1_STANDARD streaming responses pass through unredacted.
     *        TIER_2_STRICT agents get sliding-window output redaction from
     *        {@link StatefulStreamingPIIAdvisor} (order 15). Closing the gap for TIER_1 would
     *        require buffer-collect-then-emit (the F4 pattern in {@link ContentSafetyAdvisor})
     *        which collapses the streaming UX into a block-render. That trade-off is a product
     *        decision — it is intentionally not made here without explicit input.</p>
     *
     *        <p>If/when product decides TIER_1 should also redact streaming output, the fix is
     *        to add a {@code collectList().flatMapMany(...)} branch here that runs
     *        {@link DeterministicNEREngine#scrub} against the joined output and emits replacement
     *        chunks. {@link AdvisorPiiBoundaryContractTest} pins the order at 10 — any new
     *        streaming-output guard must respect that boundary.</p>
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest redactedRequest = redactRequest(request);
        return chain.nextStream(redactedRequest);
    }

    /**
     * @summary Redacts PII from the user's prompt before LLM invocation.
     * @logic 1. Extracts the agent ID and session ID from the request context.
     *        2. Resolves the applicable PII policies for that agent (or falls back to global).
     *        3. Delegates to the DeterministicNEREngine for scanning and scrubbing.
     *        4. Records each scrub event in the audit log.
     *        5. Mutates the request prompt with the sanitized text.
     */
    private ChatClientRequest redactRequest(ChatClientRequest request) {
        String originalText = request.prompt().getContents();
        if (originalText == null || originalText.isBlank()) {
            return request;
        }

        // Extract context metadata
        Map<String, Object> context = request.context();
        String agentId = context != null ? (String) context.get(AGENT_ID_KEY) : null;
        String sessionId = context != null ? (String) context.get(SESSION_ID_KEY) : null;

        // Resolve applicable policies. Per-tenant scoping (PR #979) requires an orgId; the
        // service then prefers per-agent bindings and falls back to the tenant's enabled
        // policies when no agentId is in context. If orgId is null/blank the request is
        // on a system / pre-auth path and PII filtering is skipped — we cannot guess
        // WHICH tenant's policies to apply.
        String orgId = AgentContextHolder.getOrgId();
        List<PiiPolicyEntity> policies;
        if (orgId != null && !orgId.isBlank()) {
            policies = policyService.findPoliciesForAgent(agentId, orgId);
        } else {
            policies = List.of();
        }

        if (policies.isEmpty()) {
            // No policies in scope (or no tenant resolved) — request is "clean" for metering.
            scannedClean.increment();
            return request;
        }

        // Scan and scrub
        DeterministicNEREngine.ScrubResult result = nerEngine.scrub(originalText, policies, fpeService);

        if (result.events().isEmpty()) {
            scannedClean.increment();
            return request;
        }
        scannedRedacted.increment();
        redactionEvents.increment(result.events().size());

        // Record NHI audit events as one batch — single transaction instead of N round-trips
        final String fAgentId = agentId;
        final String fSessionId = sessionId;
        final String fOrgId = orgId;
        List<PiiAuditLogEntity> entries = result.events().stream()
                .map(event -> new PiiAuditLogEntity(
                        UUID.randomUUID(),
                        fAgentId,
                        event.policyName(),
                        event.scrubStrategy(),
                        1,
                        fSessionId,
                        fOrgId))
                .toList();
        try {
            auditLogRepository.saveAll(entries);
        } catch (Exception e) {
            log.error("Failed to persist {} PII audit entries: {}", entries.size(), e.getMessage(), e);
        }

        log.info("PII Advisor: scrubbed {} detections for agent '{}' in session '{}'",
                result.events().size(), agentId, sessionId);

        // Mutate the request with sanitized text
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(
                        u -> new org.springframework.ai.chat.messages.UserMessage(result.scrubbedText())))
                .build();
    }

    /**
     * @summary Intercepts the generated LLM response (Output Guardrail) and redacts hallucinated/leaked PII.
     * @logic Uses existing framework rules. Mutates the AssistantMessage synchronously inside the response.
     */
    private ChatClientResponse redactResponse(ChatClientResponse response, ChatClientRequest request) {
        if (response == null || response.chatResponse() == null 
            || response.chatResponse().getResult() == null 
            || response.chatResponse().getResult().getOutput() == null) {
            return response;
        }

        String originalText = response.chatResponse().getResult().getOutput().getText();
        if (originalText == null || originalText.isBlank()) {
            return response;
        }

        Map<String, Object> context = request.context();
        String agentId = context != null ? (String) context.get(AGENT_ID_KEY) : null;
        String sessionId = context != null ? (String) context.get(SESSION_ID_KEY) : null;

        String orgId = AgentContextHolder.getOrgId();
        List<PiiPolicyEntity> policies = (orgId != null && !orgId.isBlank())
                ? policyService.findPoliciesForAgent(agentId, orgId)
                : List.of();

        if (policies.isEmpty()) {
            return response;
        }

        DeterministicNEREngine.ScrubResult result = nerEngine.scrub(originalText, policies, fpeService);

        if (result.events().isEmpty()) {
            return response;
        }

        final String fAgentId = agentId;
        final String fSessionId = sessionId;
        final String fOrgId = orgId;
        List<PiiAuditLogEntity> outputEntries = result.events().stream()
                .map(event -> new PiiAuditLogEntity(
                        UUID.randomUUID(), fAgentId, event.policyName() + "_OUTPUT_GUARD",
                        event.scrubStrategy(), 1, fSessionId, fOrgId))
                .toList();
        try {
            auditLogRepository.saveAll(outputEntries);
        } catch (Exception e) {
            log.error("Failed to persist {} PII Output Guard audit entries: {}", outputEntries.size(), e.getMessage(), e);
        }

        log.info("PII Output Guard: scrubbed {} detections for agent '{}' in session '{}'",
                result.events().size(), agentId, sessionId);

        return rebuildResponseWithText(response, result.scrubbedText());
    }

    /**
     * @summary Returns the scrubbed form of {@code text} using policies bound to the agent
     *          identified by {@code request.context()}. Identity if no policies match or
     *          if {@code text} is null/blank.
     * @logic Mirrors {@link #redactResponse}'s scrubbing core but does NOT mutate a response
     *        and does NOT emit a PII Output Guard audit row — the latter responsibility stays
     *        with {@code redactResponse} so the same scrub is not double-counted in
     *        {@code pii_audit_log}. Used by {@code ExtensionHookAdvisor} to scrub the
     *        LLM reply BEFORE dispatching a POST hook, since the hook advisor at order 15
     *        unwinds before the order-10 PII guard runs (closes audit F5's post-hook leak).
     */
    @Override
    public String scrub(String text, ChatClientRequest request) {
        if (text == null || text.isBlank() || request == null) {
            return text;
        }
        Map<String, Object> context = request.context();
        String agentId = context != null ? (String) context.get(AGENT_ID_KEY) : null;
        String orgId = AgentContextHolder.getOrgId();
        List<PiiPolicyEntity> policies = (orgId != null && !orgId.isBlank())
                ? policyService.findPoliciesForAgent(agentId, orgId)
                : List.of();
        if (policies.isEmpty()) {
            return text;
        }
        DeterministicNEREngine.ScrubResult result = nerEngine.scrub(text, policies, fpeService);
        return result.scrubbedText();
    }

    /**
     * @summary Rebuilds the {@link ChatClientResponse} so the assistant message carries
     *          the scrubbed text, preserving tool calls, properties, media, and response metadata.
     * @logic Uses Spring AI's public builders (verified against spring-ai-model 2.0.0-SNAPSHOT)
     *        instead of reflectively writing the {@code textContent} private field on
     *        {@code AbstractMessage}. The reflective path silently no-op'ed on a Spring AI
     *        snapshot bump that renamed or finalized the field — see audit F9.
     */
    private static org.springframework.ai.chat.client.ChatClientResponse rebuildResponseWithText(
            org.springframework.ai.chat.client.ChatClientResponse response, String newText) {
        org.springframework.ai.chat.messages.AssistantMessage original =
                (org.springframework.ai.chat.messages.AssistantMessage) response.chatResponse().getResult().getOutput();
        org.springframework.ai.chat.messages.AssistantMessage replacement =
                org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content(newText)
                        .properties(original.getMetadata())
                        .toolCalls(original.getToolCalls())
                        .media(original.getMedia())
                        .build();
        org.springframework.ai.chat.model.Generation newGeneration =
                new org.springframework.ai.chat.model.Generation(
                        replacement,
                        response.chatResponse().getResult().getMetadata());
        org.springframework.ai.chat.model.ChatResponse newChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(newGeneration),
                        response.chatResponse().getMetadata());
        return org.springframework.ai.chat.client.ChatClientResponse.builder()
                .chatResponse(newChatResponse)
                .context(response.context())
                .build();
    }
}
