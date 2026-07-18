package com.operativus.agentmanager.compute.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

/**
 * Domain Responsibility: Intercepts and compresses excessively large string payloads using a fast routing model before they breach context windows.
 * State: Stateless
 */
@Service
public class ToolCompressionService {

    private static final Logger log = LoggerFactory.getLogger(ToolCompressionService.class);
    private final com.operativus.agentmanager.compute.service.AgentModelResolverService modelResolver;
    private final com.operativus.agentmanager.core.registry.SettingsOperations settingsService;

    public ToolCompressionService(com.operativus.agentmanager.compute.service.AgentModelResolverService modelResolver, com.operativus.agentmanager.core.registry.SettingsOperations settingsService) {
        this.modelResolver = modelResolver;
        this.settingsService = settingsService;
    }

    /**
     * Compress the provided payload if it exceeds the token/character threshold limit.
     */
    public String compressIfRequired(String toolName, String payload, com.operativus.agentmanager.core.model.definitions.AgentDefinition agentDef) {
        int activeThreshold = (agentDef != null && agentDef.compressionThreshold() != null) ? agentDef.compressionThreshold() : settingsService.getCompressionThresholdChars(8000);
        
        if (payload == null || payload.length() <= activeThreshold) {
            return payload; // No compression needed or service disabled
        }
        
        String optimizationModelId = (agentDef != null) ? agentDef.optimizationModelId() : null;
        ChatModel targetModel = modelResolver.resolveOptimizationModel(optimizationModelId);
        
        if (targetModel == null) {
            log.warn("Tool [{}] returned massive payload, but no active optimization model was found. Compression aborted.", toolName);
            return payload; // Service genuinely disabled
        }
        
        ChatClient chatClient = ChatClient.builder(targetModel).build();

        log.info("Tool [{}] returned payload of size {} chars. Exceeds {} threshold. Triggering compression summarization...", toolName, payload.length(), activeThreshold);

        String template = "You are a payload compression engine parsing the raw output of a tool called '{toolName}'. " +
                "The system requires only the factual, relevant, extracted information strictly mapped without conversational fluff. " +
                "Summarize the following massive raw output down to maximum precision facts, preserving code chunks or explicit data if present.\n\n" +
                "--- RAW OUTPUT BLOCK:\n{payload}\n--- END RAW OUTPUT BLOCK";

        PromptTemplate promptTemplate = new PromptTemplate(template);
        promptTemplate.add("toolName", toolName);
        promptTemplate.add("payload", payload);

        try {
            String summary = chatClient.prompt(promptTemplate.create()).call().content();
            log.info("Tool [{}] payload compressed successfully from {} to {} chars.", toolName, payload.length(), summary.length());
            return "--- OPTIMIZED DATA PAYLOAD (COMPRESSED FROM " + payload.length() + " CHARS VIA FAST-ROUTER) ---\n" + summary;
        } catch (Exception e) {
            log.error("Failed to compress massive tool payload for [{}]. Falling back to safe structural stub.", toolName, e);
            // Safe JSON-friendly fallback to prevent crashing the Reasoner
            return "{ \"error_constraint\": \"The tool successfully executed but the resulting payload was " + payload.length() + " characters long, breaking context limits. The compression engine failed to distill it. Please formulate a more specific request or tool input.\" }";
        }
    }
}
