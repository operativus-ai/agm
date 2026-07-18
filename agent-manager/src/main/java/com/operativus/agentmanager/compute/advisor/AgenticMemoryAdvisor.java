package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.core.registry.MemoryOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;


import java.util.List;

/**
 * Domain Responsibility: Intercepts Spring AI ChatClient requests to natively inject User/Entity specific Long-term memory logic constraints.
 * State: Stateless
 */
public class AgenticMemoryAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AgenticMemoryAdvisor.class);

    private final MemoryOperations memoryOperations;
    private final String userId;

    public AgenticMemoryAdvisor(MemoryOperations memoryOperations, String userId) {
        this.memoryOperations = memoryOperations;
        this.userId = userId;
    }

    @Override
    public String getName() {
        return "AgenticMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        // Runs after PII/Safety, before external search
        return 50;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(augmentRequest(request));
    }

    private ChatClientRequest augmentRequest(ChatClientRequest request) {
        if (this.userId == null || this.userId.isBlank()) {
            return request;
        }

        try {
            // Fetch User specific memory bounds (Agentic DB layer isolating user_id semantic rules)
            List<String> userRules = this.memoryOperations.searchUserMemories(this.userId);
            
            if (userRules == null || userRules.isEmpty()) {
                return request;
            }
            
            log.info("AgenticMemoryAdvisor: Injecting {} semantic rules into LLM Context for User: {}", userRules.size(), this.userId);
            
            StringBuilder memoryContext = new StringBuilder();
            memoryContext.append("\n--- LEARNED MEMORY RULES (STRICT COMPLIANCE REQUIRED) ---\n");
            for (String rule : userRules) {
                memoryContext.append("- ").append(rule).append("\n");
            }
            memoryContext.append("----------------------------------------------------------\n");

            // Re-build prompt instructions, unifying SystemMessages safely
            List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
            StringBuilder unifiedSystemText = new StringBuilder();
            
            if (request.prompt() != null && request.prompt().getInstructions() != null) {
                for (org.springframework.ai.chat.messages.Message msg : request.prompt().getInstructions()) {
                    if (msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM) {
                        unifiedSystemText.append(msg.getText()).append("\n\n");
                    } else {
                        messages.add(msg);
                    }
                }
            }
            
            unifiedSystemText.append(memoryContext);
            messages.add(0, new org.springframework.ai.chat.messages.SystemMessage(unifiedSystemText.toString()));

            org.springframework.ai.chat.prompt.Prompt augmentedPrompt = new org.springframework.ai.chat.prompt.Prompt(
                 messages,
                 request.prompt() != null ? request.prompt().getOptions() : null
            );

            return request.mutate().prompt(augmentedPrompt).build();
            
        } catch (Exception e) {
            log.error("Failed to inject Agentic Memory bounds for LLM context.", e);
            return request;
        }
    }
}
