package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.compute.security.DeterministicNEREngine;
import ai.operativus.agentmanager.compute.security.FormatPreservingEncryptionService;
import ai.operativus.agentmanager.compute.security.PiiAuditLogEntity;
import ai.operativus.agentmanager.compute.security.PiiAuditLogRepository;
import ai.operativus.agentmanager.compute.security.PiiPolicyEntity;
import ai.operativus.agentmanager.compute.security.PiiPolicyService;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.ComplianceTier;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Enterprise Phase 2 Output Guardrails for real-time SSE LLM streams.
 * State: Stateful (Project Reactor Sliding Window Buffer)
 * Logic: Unlike the Phase 1 synchronous advisor, this bean utilizes a stateful chunking buffer (.bufferUntil) 
 * to temporarily halt the SSE stream until a distinct word boundary is detected. This prevents "partial token"
 * leakage (e.g. leaking "424-" "22" "-1" across three SSE chunks avoiding regex detection).
 * Only applied to agents dynamically designated as TIER_2_STRICT.
 */
@Component
public class StatefulStreamingPIIAdvisor implements StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(StatefulStreamingPIIAdvisor.class);
    
    private final PiiPolicyService policyService;
    private final DeterministicNEREngine nerEngine;
    private final FormatPreservingEncryptionService fpeService;
    private final PiiAuditLogRepository auditLogRepository;
    private final AgentRepository agentRepository;

    public StatefulStreamingPIIAdvisor(PiiPolicyService policyService,
                                       DeterministicNEREngine nerEngine,
                                       FormatPreservingEncryptionService fpeService,
                                       PiiAuditLogRepository auditLogRepository,
                                       AgentRepository agentRepository) {
        this.policyService = policyService;
        this.nerEngine = nerEngine;
        this.fpeService = fpeService;
        this.auditLogRepository = auditLogRepository;
        this.agentRepository = agentRepository;
    }

    @Override
    public String getName() {
        return "StatefulStreamingPIIAdvisor";
    }

    @Override
    public int getOrder() {
        return 15; // Run right after standard PII input redaction
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Map<String, Object> context = request.context();
        String agentId = context != null ? (String) context.get(PIIAnonymizationAdvisor.AGENT_ID_KEY) : null;
        String sessionId = context != null ? (String) context.get(PIIAnonymizationAdvisor.SESSION_ID_KEY) : null;

        if (agentId == null || agentId.isBlank()) {
            // No agentId in the request context — system operations or a misconfigured chain
            // (AgentIdInjectionAdvisor must run earlier to populate these keys).
            return chain.nextStream(request);
        }

        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null || agent.getComplianceTier() != ComplianceTier.TIER_2_STRICT) {
            // Bypass latency tax for Tier 1 agents
            return chain.nextStream(request);
        }

        // org_id was eagerly stamped into AgentEntity at create time and is tenant-stable for the
        // life of the agent row, so resolve via the entity rather than ScopedValues — the advisor
        // chain runs on Reactor scheduler threads where AgentContextHolder bindings are NOT visible.
        String orgId = agent.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            // Defensive: pre-tenant-scoping rows may carry NULL org_id; treat as bypass.
            return chain.nextStream(request);
        }
        List<PiiPolicyEntity> policies = policyService.findPoliciesForAgent(agentId, orgId);
        if (policies.isEmpty()) {
            return chain.nextStream(request);
        }

        log.debug("StatefulStreamingPIIAdvisor: Enabling sliding-window stream chunking for Tier 2 strict agent {}", agentId);

        final String capturedAgentId = agentId;
        final String capturedSessionId = sessionId;
        final String capturedOrgId = orgId;
        return chain.nextStream(request)
                .bufferUntil(response -> {
                    String text = extractText(response);
                    // Buffer tokens in memory until a word boundary or punctuation occurs
                    return text != null && text.matches(".*[\\s.,;!?\\n]+.*");
                })
                .map(batch -> processBatch(batch, policies, capturedAgentId, capturedSessionId, capturedOrgId));
    }

    private ChatClientResponse processBatch(List<ChatClientResponse> batch, List<PiiPolicyEntity> policies, String agentId, String sessionId, String orgId) {
        if (batch.isEmpty()) return null;

        String combinedText = batch.stream()
                .map(this::extractText)
                .filter(t -> t != null)
                .collect(Collectors.joining());

        if (combinedText.isBlank()) {
            return batch.get(batch.size() - 1);
        }

        DeterministicNEREngine.ScrubResult result = nerEngine.scrub(combinedText, policies, fpeService);

        if (!result.events().isEmpty()) {
            for (DeterministicNEREngine.ScrubEvent event : result.events()) {
                try {
                    PiiAuditLogEntity auditEntry = new PiiAuditLogEntity(
                            UUID.randomUUID(), agentId, event.policyName() + "_SSE_OUTPUT_GUARD",
                            event.scrubStrategy(), 1, sessionId, orgId);
                    auditLogRepository.save(auditEntry);
                } catch (Exception e) {
                    log.error("Failed to persist PII SSE Guard log: {}", e.getMessage());
                }
            }
            log.info("PII SSE Guard: scrubbed {} real-time detections for agent '{}'", result.events().size(), agentId);
        }

        // Return the final response from the batch, functionally mutating its content to the scrubbed text
        ChatClientResponse finalResponse = batch.get(batch.size() - 1);
        return mutateResponseText(finalResponse, result.scrubbedText());
    }

    private String extractText(ChatClientResponse response) {
        if (response != null && response.chatResponse() != null 
            && response.chatResponse().getResult() != null 
            && response.chatResponse().getResult().getOutput() != null) {
            return response.chatResponse().getResult().getOutput().getText();
        }
        return null;
    }

    /**
     * @summary Rebuilds the {@link ChatClientResponse} so the assistant message carries the
     *          scrubbed text, preserving tool calls, properties, media, and response metadata.
     * @logic Uses Spring AI's public builders (verified against spring-ai-model 2.0.0-SNAPSHOT)
     *        instead of reflectively writing the {@code textContent} private field on
     *        {@code AbstractMessage}. The reflective path silently no-op'ed on a Spring AI
     *        snapshot bump that renamed or finalized the field — see audit F9.
     */
    private ChatClientResponse mutateResponseText(ChatClientResponse response, String newText) {
        if (response == null || response.chatResponse() == null
            || response.chatResponse().getResult() == null
            || response.chatResponse().getResult().getOutput() == null) {
            return response;
        }

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
        return ChatClientResponse.builder()
                .chatResponse(newChatResponse)
                .context(response.context())
                .build();
    }
}
