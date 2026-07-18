package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.entity.AgentMessage;
import ai.operativus.agentmanager.core.entity.AgentSession;
import ai.operativus.agentmanager.control.repository.MessageRepository;
import ai.operativus.agentmanager.control.repository.SessionRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Implements Spring AI's ChatMemory interface to provide Postgres-backed short-term conversational history.
 * State: Stateless (Provides persistence for stateful chat sessions)
 */
@Service
public class PersistentChatMemory implements ChatMemory {

    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final ai.operativus.agentmanager.compute.service.SessionSummarizationService summarizationService;

    public PersistentChatMemory(MessageRepository messageRepository, SessionRepository sessionRepository, ai.operativus.agentmanager.compute.service.SessionSummarizationService summarizationService) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.summarizationService = summarizationService;
    }

    /**
     * @summary Appends a list of Spring AI Messages to a specific conversation's history.
     * @logic Validates/Creates the underlying AgentSession to enforce referential integrity, maps Spring AI Message abstractions to internal JPA AgentMessage entities, and batch saves the entities to the repository.
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        // Ensure session exists before adding messages
        ensureSessionExists(conversationId);
        
        List<AgentMessage> entities = messages.stream()
                .map(msg -> mapToEntity(conversationId, msg))
                .collect(Collectors.toList());
        messageRepository.saveAll(entities);
        
        // Trigger background asynchronous summarization evaluation
        summarizationService.evaluateSessionForSummarization(conversationId);
    }

    /**
     * @summary Verifies the existence of a session and creates a default one if missing.
     * @logic Queries the SessionRepository by ID. If not found, initializes a new session with default user/org fallback values to satisfy schema constraints.
     */
    private void ensureSessionExists(String sessionId) {
        boolean encryptionRequired = ai.operativus.agentmanager.core.callback.AgentContextHolder.getRequiresEncryption();
        String currentAgentId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getAgentId();
        sessionRepository.findById(sessionId).ifPresentOrElse(
                session -> {
                    session.setUpdatedAt(LocalDateTime.now());
                    session.setRequiresEncryption(encryptionRequired);
                    // Backfill agentId if it was previously missing
                    if (session.getAgentId() == null && currentAgentId != null) {
                        session.setAgentId(currentAgentId);
                    }
                    sessionRepository.save(session);
                },
                () -> {
                    AgentSession session = new AgentSession();
                    session.setSessionId(sessionId);
                    session.setAgentId(currentAgentId);

                    String userId = ai.operativus.agentmanager.control.config.SecurityContextUtils.resolveCurrentUserId();
                    session.setUserId(userId);
                    // M1 production fix: read orgId from the agent context, not from userId.
                    // Previously this wrote the user's username into agent_sessions.org_id so
                    // two users in the same org created sessions with DIFFERENT orgIds — every
                    // tenant-scoped query on session.org_id returned wrong rows. Fall back to
                    // userId only when no agent context is bound (e.g., direct API callers
                    // that bypass the run-time ScopedValue chain).
                    String ctxOrgId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId();
                    session.setOrgId((ctxOrgId != null && !ctxOrgId.isBlank()) ? ctxOrgId : userId);
                    session.setCreatedAt(LocalDateTime.now());
                    session.setUpdatedAt(LocalDateTime.now());
                    session.setRequiresEncryption(encryptionRequired);
                    sessionRepository.save(session);
                }
        );
    }

    /**
     * @summary Retrieves the most recent N messages for a given conversation.
     * @logic Fetches all messages for the session ordered chronologically, calculates the sublist boundary to extract only the last N messages, and maps JPA entities back to Spring AI Message abstractions.
     */
    public List<Message> get(String conversationId, int lastN) {
        // Enforce the constraint for retrieving un-summarized immediate messages. 
        // Operativus targets ~5. If the request demands > 10, default to 10 immediate messages maximum to prevent context drift.
        int immediateWindow = Math.min(lastN, 10);
        
        List<AgentMessage> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(conversationId);
        int total = all.size();
        int start = Math.max(0, total - immediateWindow);
        
        List<Message> reconstruction = new java.util.ArrayList<>();
        
        // Check for an existing historical session summary
        sessionRepository.findById(conversationId).ifPresent(session -> {
            if (session.getSummaryBlob() != null && !session.getSummaryBlob().isBlank()) {
                String summaryContext = "--- PREVIOUS SESSION SUMMARY CONTEXT ---\n" + session.getSummaryBlob() + "\n--------------------";
                reconstruction.add(new org.springframework.ai.chat.messages.SystemMessage(summaryContext));
            }
        });
        
        List<Message> immediateMessages = all.subList(start, total).stream()
                .map(this::mapToMessage)
                .collect(Collectors.toList());
                
        reconstruction.addAll(immediateMessages);
        return reconstruction;
    }

    /**
     * @summary Retrieves the full conversation history (capped at 100) for a session.
     * @logic Overrides the base ChatMemory interface method and delegates to the bounded get() method with a hard limit of 100 to prevent unbounded memory loading.
     */
    @Override
    public List<Message> get(String conversationId) {
        return get(conversationId, 100); // Default to last 100 if interface demands full history
    }

    /**
     * @summary Clears the conversation history (Currently suppressed for safety).
     * @logic Intentionally left blank to prevent accidental truncation of audit trails by default Spring AI behaviors.
     */
    @Override
    public void clear(String conversationId) {
        // Not implemented for safety
    }

    /**
     * @summary Translates a Spring AI Message into a JPA AgentMessage entity.
     * @logic Extracts plain text content, message type enumeration, and metadata map into a new AgentMessage.
     */
    private AgentMessage mapToEntity(String sessionId, Message msg) {
        AgentMessage entity = new AgentMessage();
        entity.setSessionId(sessionId);
        entity.setContent(msg.getText()); // Changed from getContent() to getText()
        entity.setMessageType(msg.getMessageType().name());
        entity.setMetadata(msg.getMetadata());
        entity.setRequiresEncryption(ai.operativus.agentmanager.core.callback.AgentContextHolder.getRequiresEncryption());
        return entity;
    }

    /**
     * @summary Translates a JPA AgentMessage entity back into a specific Spring AI Message subclass.
     * @logic Switches on the saved MessageType to instantiate UserMessage, AssistantMessage, or SystemMessage, and provides fallback reconstruction for Tool messages.
     */
    private Message mapToMessage(AgentMessage entity) {
        MessageType type = MessageType.valueOf(entity.getMessageType());
        switch (type) {
            case USER:
                return new UserMessage(entity.getContent()); // Simple constructor
            case ASSISTANT:
                return new AssistantMessage(entity.getContent()); // Simple constructor
            case SYSTEM:
                return new SystemMessage(entity.getContent()); // Simple constructor
            case TOOL:
                // Fallback for Tool messages until we have proper reconstruction logic
                 return new SystemMessage("Tool Result: " + entity.getContent());
            default:
                throw new IllegalArgumentException("Unknown message type: " + type);
        }
    }
}
