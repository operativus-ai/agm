package com.operativus.agentmanager.compute.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.operativus.agentmanager.core.model.ModelFamily;
import com.operativus.agentmanager.core.model.ModelProviderNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Addresses token bloat by intercepting conversational memory. Maintains session history and truncates sessions exceeding a certain message limit into a highly compressed EpisodicSummary block before appending to the execution context.
 * State: Stateful (maintains in-memory session history map)
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);

    // Thread-safe transient in-memory store for session histories
    private final Map<String, List<Message>> sessionMemories = new ConcurrentHashMap<>();
    
    private final int messageLimit;
    private final String summarizationModel;
    private final java.util.Map<String, org.springframework.ai.chat.model.ChatModel> chatModels;

    public SessionSummaryService(
            java.util.Map<String, org.springframework.ai.chat.model.ChatModel> chatModels,
            @Value("${agent.memory.max-session-messages:10}") int messageLimit,
            @Value("${agent.memory.summary-model:gemini-2.5-flash}") String summarizationModel) {
        this.chatModels = chatModels;
        this.messageLimit = messageLimit;
        this.summarizationModel = summarizationModel;
    }

    /**
     * @summary Retrieves prior conversational context for a session, triggering episodic summarization if the message limit threshold is hit.
     * @logic
     * Fetches the current session history from the in-memory map. If the number of messages exceeds the configured limit, it invokes `summarizeAndCompress` to condense the history into a single dense summary block, preventing token window exhaustion. Returns a copy of the history to prevent concurrent modification exceptions.
     */
    public List<Message> getOptimizedHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ArrayList<>();
        }

        List<Message> history = sessionMemories.getOrDefault(sessionId, new ArrayList<>());

        if (history.size() > messageLimit) {
            log.info("Session {} exceeded message limit ({} > {}). Triggering Episodic Summarization.", sessionId, history.size(), messageLimit);
            history = summarizeAndCompress(sessionId, history);
        }

        return new ArrayList<>(history); // return a copy to prevent concurrent modification issues
    }
    
    /**
     * @summary Appends a new UserMessage to the session's conversational memory.
     * @logic Retrieves the session history list (creating it if absent) and adds the raw user text as a UserMessage.
     */
    public void addUserInput(String sessionId, String userInput) {
        if (sessionId == null || sessionId.isBlank()) return;
        List<Message> history = sessionMemories.computeIfAbsent(sessionId, k -> new ArrayList<>());
        history.add(new UserMessage(userInput));
    }
    
    /**
     * @summary Appends a new AssistantMessage to the session's conversational memory.
     * @logic Retrieves the session history list (creating it if absent) and adds the LLM's raw text response as an AssistantMessage.
     */
    public void addAssistantResponse(String sessionId, String response) {
        if (sessionId == null || sessionId.isBlank()) return;
        List<Message> history = sessionMemories.computeIfAbsent(sessionId, k -> new ArrayList<>());
        // Spring AI 1.0+ changed AssistantMessage signature to require metadata or just string
        history.add(new AssistantMessage(response));
    }

    /**
     * @summary Compresses a long conversation history into a single dense episodic summary block using a fast LLM.
     * @logic 
     * 1. Concatenates the raw message history into a single text block.
     * 2. Resolves the target summarization ChatModel (e.g. gemini-2.5-flash) by checking the configured prefix and available Spring AI beans.
     * 3. Prompts the summarization model to extract facts, decisions, and context sequentially while stripping pleasantries.
     * 4. Replaces the entire session history with a single SystemMessage containing the new episodic summary, effectively resetting the token count while preserving context.
     */
    private List<Message> summarizeAndCompress(String sessionId, List<Message> history) {
        try {
            StringBuilder rawHistory = new StringBuilder();
            for (Message msg : history) {
                // Spring AI 1.0+ uses msg.getText(), not getContent()
                rawHistory.append(msg.getMessageType().name()).append(": ").append(msg.getText()).append("\n");
            }

            org.springframework.ai.chat.model.ChatModel targetModel = null;
            String effectiveModelId = summarizationModel != null ? summarizationModel.toLowerCase() : "";
            ModelFamily family = ModelFamily.fromModelId(effectiveModelId);

            if (family == ModelFamily.OPENAI) {
                targetModel = chatModels.get(ModelProviderNames.OPENAI);
            } else if (family == ModelFamily.ANTHROPIC) {
                targetModel = chatModels.get(ModelProviderNames.ANTHROPIC);
            } else if (family == ModelFamily.GOOGLE) {
                targetModel = chatModels.get(ModelProviderNames.GOOGLE);
                if (targetModel == null) {
                    targetModel = chatModels.get(ModelProviderNames.GEMINI_FALLBACK);
                }
            }

            boolean isFallback = false;
            if (targetModel == null && !chatModels.isEmpty()) {
                targetModel = chatModels.values().iterator().next();
                isFallback = true;
                log.warn("Summarization model {} provider missing. Falling back to default available provider.", summarizationModel);
            }

            if (targetModel == null) {
                log.error("No ChatModel available for Episodic Summarization.");
                return history;
            }

            ChatClient client = ChatClient.builder(targetModel).build();
            String prompt = "Summarize the following conversation history into a dense, episodic summary. " +
                            "Capture important facts, decisions, context, and user intents sequentially. " +
                            "Do not include pleasantries. Return the summary alone.\n\n" + rawHistory.toString();

            var request = client.prompt().user(prompt);
            if (!isFallback && summarizationModel != null && !summarizationModel.isBlank()) {
                request = request.options(ChatOptions.builder().model(summarizationModel));
            }

            String summaryText = request.call().content();

            log.info("Successfully collapsed history for session {}", sessionId);

            // Replace old history with the new episodic summary block
            List<Message> newHistory = new ArrayList<>();
            newHistory.add(new SystemMessage("EPISODIC SUMMARY OF PRIOR CONTEXT:\n" + summaryText));
            sessionMemories.put(sessionId, newHistory);
            
            return newHistory;
        } catch (Exception e) {
            log.error("Failed to generate episodic summary for session {}. Retaining raw history.", sessionId, e);
            return history; // fallback
        }
    }
}
