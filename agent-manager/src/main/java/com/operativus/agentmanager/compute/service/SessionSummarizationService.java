package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.control.repository.MessageRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.entity.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Domain Responsibility: Asynchronously background-summarizes long conversational sessions to maintain context scale constraints and prevent amnesia.
 * Execution: Runs concurrently on Virtual Threads to maintain Agent Orchestration throughput.
 */
@Service
public class SessionSummarizationService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummarizationService.class);

    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final com.operativus.agentmanager.compute.service.AgentModelResolverService modelResolver;
    private final com.operativus.agentmanager.core.registry.SettingsOperations settingsService;
    private final com.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry;

    public SessionSummarizationService(MessageRepository messageRepository,
                                       SessionRepository sessionRepository,
                                       com.operativus.agentmanager.compute.service.AgentModelResolverService modelResolver,
                                       com.operativus.agentmanager.core.registry.SettingsOperations settingsService,
                                       com.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.modelResolver = modelResolver;
        this.settingsService = settingsService;
        this.agentRegistry = agentRegistry;
    }

    /**
     * Examines the session history. If it exceeds the threshold limit, it triggers a condensation process 
     * on the oldest messages and updates the session's summary Blob.
     */
    @Async
    public void evaluateSessionForSummarization(String sessionId) {

        sessionRepository.findById(sessionId).ifPresent(session -> {
            int activeThreshold = settingsService.getSummarizationThresholdTurns(20);
            String optimizationModelId = null;
            if (session.getAgentId() != null) {
                com.operativus.agentmanager.core.model.definitions.AgentDefinition def = agentRegistry.findById(session.getAgentId(), session.getOrgId());
                if (def != null) {
                    if (def.summarizationThreshold() != null) {
                        activeThreshold = def.summarizationThreshold();
                    }
                    optimizationModelId = def.optimizationModelId();
                }
            }
            
            ChatModel targetModel = modelResolver.resolveOptimizationModel(optimizationModelId);
            if (targetModel == null) {
                log.warn("SessionSummarizationService could not resolve an optimization model target. Summaries will be disabled for session {}", sessionId);
                return;
            }
            
            ChatClient localizedClient = ChatClient.builder(targetModel).build();

            List<AgentMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            
            // Only summarize once we accumulate substantial conversational depth
            // We want to leave the most recent 10 turns untouched to preserve immediate conversational context.
            if (messages.size() <= activeThreshold) {
                return;
            }

            int toSummarizeCount = messages.size() - 5; // Leave the last 5 completely unmodified
            List<AgentMessage> targetMessages = messages.subList(0, toSummarizeCount);

            String existingSummary = session.getSummaryBlob() != null ? session.getSummaryBlob() : "No previous summary.";
            
            StringBuilder conversationDraft = new StringBuilder();
            for (AgentMessage msg : targetMessages) {
                conversationDraft.append("[").append(msg.getMessageType()).append("]: ").append(msg.getContent()).append("\n");
            }

            String template = "You are a professional session summarizer for an AI Agent system. " +
                    "Your goal is to compress a conversation's history into a dense, highly factual summary block.\n" +
                    "Current Narrative Summary: {existingSummary}\n\n" +
                    "--- NEW CONVERSATION TO INTEGRATE:\n{conversation}\n--- END CONVERSATION\n\n" +
                    "Return ONLY the updated narrative summary encompassing both the previous summary and the new conversation. " +
                    "Include major decisions made, preferences expressed by the user, specific parameters provided, and factual discoveries. " +
                    "Do NOT use markdown code blocks or conversational text. Return plain text only.";

            PromptTemplate promptTemplate = new PromptTemplate(template);
            promptTemplate.add("existingSummary", existingSummary);
            promptTemplate.add("conversation", conversationDraft.toString());

            try {
                String newSummary = localizedClient.prompt(promptTemplate.create()).call().content();
                session.setSummaryBlob(newSummary.trim());
                sessionRepository.save(session);
                
                // Explicitly delete the messages that were summarized to permanently free up the database?
                // Usually for auditing we retain them. In Operativus, they might drop them. For AGM architecture, we retain them in DB
                // but rely on `PersistentChatMemory` to strictly filter out messages that are older than the summary boundary.
                
                log.info("Session {} summary successfully generated/updated. Extracted {} old turns into compression.", sessionId, targetMessages.size());
            } catch (Exception e) {
                log.error("Failed to generate session summary for {}", sessionId, e);
            }
        });
    }
}
