package com.operativus.agentmanager.compute.memory;

import com.operativus.agentmanager.control.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Domain Responsibility: Handles automated background extraction of memories (`update_memory_on_run=True`).
 * Utilizes Java 21 Virtual Threads to asynchronously query the LLM to summarize conversation context
 * and store it immediately into the vector store without blocking the primary request thread.
 */
@Service
public class AutomaticMemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(AutomaticMemoryExtractor.class);
    
    // Leverage Java 21 Virtual Threads for lightweight, high-throughput blocking tasks
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final MemoryService memoryService;
    private final org.springframework.ai.chat.client.ChatClient chatClient;

    public AutomaticMemoryExtractor(MemoryService memoryService, org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder) {
        this.memoryService = memoryService;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * @summary Triggers an asynchronous virtual thread to extract user preferences from recent chat history.
     */
    public void startImplicitMemoryExtraction(String sessionId, String userId, String latestInput, String latestOutput) {
        log.debug("Dispatching Virtual Thread for asynchronous memory extraction. [Session: {}]", sessionId);

        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        // F18 — fresh VT does NOT inherit JDK 21 ScopedValues. Rebind AgentContextHolder bindings
        // (orgId, userId, sessionId, etc.) so the chatClient advisor chain sees the right tenant
        // and the persisted memory carries correct attribution.
        final com.operativus.agentmanager.core.callback.AgentContextSnapshot snapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();

        virtualThreadExecutor.submit(() -> {
            try {
                org.springframework.security.core.context.SecurityContextHolder.setContext(context);
                snapshot.run(() -> {
                    log.info("Virtual Thread (AutomaticMemoryExtractor) analyzing conversational context for user: {}", userId);

                    String promptTemplate = "Analyze the following message. If it contains a preference about the user, return the exact preference. " +
                                            "If it contains a procedural best practice, return the best practice. Otherwise, return 'NONE'.\n" +
                                            "User input: {input}\nResponse: {output}";

                    String response = chatClient.prompt()
                            .user(u -> u.text(promptTemplate)
                                    .param("input", latestInput)
                                    .param("output", latestOutput))
                            .call()
                            .content();

                    if (response != null && !response.trim().equalsIgnoreCase("NONE")) {
                        // Pre-cultural-memory removal this branched on heuristic keywords
                        // (preference/practice/protocol) to route "best practices" into
                        // CulturalKnowledge and "preferences" into per-user memory. With
                        // cultural memory dropped, every non-NONE extraction lands in
                        // per-user memory; the classifier didn't earn its keep at MVP
                        // (no UI ever consumed CulturalKnowledge rules).
                        memoryService.addMemory(response, userId);
                    }
                });
            } catch (Exception e) {
                log.error("Virtual Thread memory extraction failed for Session: {}", sessionId, e);
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });
    }
}
