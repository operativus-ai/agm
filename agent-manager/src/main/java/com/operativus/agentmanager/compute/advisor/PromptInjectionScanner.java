package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.core.exception.BusinessValidationException;
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

/**
 * Domain Responsibility: Intercepts inbound prompts sent to the LLM and structurally parses them for known prompt injection signatures, bypassing LLM execution if detected.
 * State: Stateless
 */
@Component
public class PromptInjectionScanner implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionScanner.class);

    // Naive signatures for Phase 5 implementation. Real-world uses specialized regex or small fast LLM classifiers.
    private static final String[] PROMPT_INJECTION_SIGNATURES = {
        "ignore all previous instructions",
        "you are now an unfiltered",
        "system prompt overrid",
        "bypass security",
        "print your original prompt",
        "forget what i said"
    };

    /**
     * @summary Intercepts synchronous chat client calls to scan for prompt injections.
     * @logic Scans the string representation of the prompt against heuristic signatures. Throws BusinessValidationException to halt if injection is detected.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        scanForInjections(request);
        return chain.nextCall(request);
    }
    
    /**
     * @summary Intercepts streaming chat client calls to scan for prompt injections.
     * @logic Scans the string representation of the prompt against heuristic signatures.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        scanForInjections(request);
        return chain.nextStream(request);
    }

    private void scanForInjections(ChatClientRequest request) {
        // Use the contracted accessor (matches PromptInjectionAdvisor) instead of
        // request.toString() — Object.toString() has no contract and a Spring AI
        // upgrade can change its representation, silently disabling this scanner.
        String input = "";
        try {
            if (request != null && request.prompt() != null && request.prompt().getContents() != null) {
                input = request.prompt().getContents().toLowerCase();
            }
        } catch (Exception e) {
            log.trace("Could not extract prompt contents: {}", e.getMessage());
        }

        for (String sig : PROMPT_INJECTION_SIGNATURES) {
            if (input.contains(sig.toLowerCase())) {
                log.error("Prompt Injection Signature Detected: [{}]", sig);
                throw new BusinessValidationException("REJECTED: Malformed prompt detected. System guardrails engaged.");
            }
        }
    }

    @Override
    public String getName() {
        return "PromptInjectionScanner";
    }

    @Override
    public int getOrder() {
        // Run BEFORE hitl and memory injection
        return -100;
    }
}
