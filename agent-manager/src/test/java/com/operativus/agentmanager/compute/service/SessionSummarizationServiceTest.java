package com.operativus.agentmanager.compute.service;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.operativus.agentmanager.control.repository.MessageRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.entity.AgentMessage;
import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.SettingsOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SessionSummarizationServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private AgentModelResolverService modelResolver;
    @Mock
    private SettingsOperations settingsService;
    @Mock
    private AgentRegistry agentRegistry;
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatModel mockChatModel;

    private SessionSummarizationService service;

    @BeforeEach
    void setUp() {
        service = new SessionSummarizationService(
                messageRepository, sessionRepository, modelResolver,
                settingsService, agentRegistry
        );
    }

    @Test
    void evaluateSession_SessionNotFound_DoesNothing() {
        when(sessionRepository.findById("missing_session")).thenReturn(Optional.empty());

        service.evaluateSessionForSummarization("missing_session");

        verify(modelResolver, never()).resolveOptimizationModel(any());
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(any());
    }

    @Test
    void evaluateSession_OptimizationModelNull_ReturnsEarly() {
        AgentSession session = mock(AgentSession.class);
        lenient().when(session.getSessionId()).thenReturn("session1");
        
        when(sessionRepository.findById("session1")).thenReturn(Optional.of(session));
        lenient().when(settingsService.getSummarizationThresholdTurns(20)).thenReturn(20);
        when(modelResolver.resolveOptimizationModel(null)).thenReturn(null);

        service.evaluateSessionForSummarization("session1");

        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(any());
    }

    @Test
    void evaluateSession_MessagesBelowThreshold_NoSummarization() {
        AgentSession session = mock(AgentSession.class);
        lenient().when(session.getSessionId()).thenReturn("session1");
        
        when(sessionRepository.findById("session1")).thenReturn(Optional.of(session));
        when(settingsService.getSummarizationThresholdTurns(20)).thenReturn(5);
        when(modelResolver.resolveOptimizationModel(null)).thenReturn(mockChatModel);

        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            AgentMessage msg = mock(AgentMessage.class);
            lenient().when(msg.getMessageType()).thenReturn("USER");
            lenient().when(msg.getContent()).thenReturn("hello");
            messages.add(msg);
        }
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session1")).thenReturn(messages);

        service.evaluateSessionForSummarization("session1");

        verify(mockChatModel, never()).call(any(Prompt.class));
        verify(sessionRepository, never()).save(any(AgentSession.class));
    }

    @Test
    void evaluateSession_MessagesAboveThreshold_SummarizesAndSaves() {
        AgentSession session = mock(AgentSession.class);
        lenient().when(session.getSessionId()).thenReturn("session1");
        lenient().when(session.getAgentId()).thenReturn("agent1");
        lenient().when(session.getSummaryBlob()).thenReturn("Old summary");

        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.summarizationThreshold()).thenReturn(10); // Custom agent threshold
        lenient().when(def.optimizationModelId()).thenReturn("opt-model");
        when(agentRegistry.findById(eq("agent1"), any())).thenReturn(def);
        when(sessionRepository.findById("session1")).thenReturn(Optional.of(session));
        when(settingsService.getSummarizationThresholdTurns(20)).thenReturn(20);
        when(modelResolver.resolveOptimizationModel("opt-model")).thenReturn(mockChatModel);

        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            AgentMessage msg = mock(AgentMessage.class);
            lenient().when(msg.getMessageType()).thenReturn("USER");
            lenient().when(msg.getContent()).thenReturn("message " + i);
            messages.add(msg);
        }
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session1")).thenReturn(messages);

        ChatResponse mockResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("New awesome summary"))));
        when(mockChatModel.call(any(Prompt.class))).thenReturn(mockResponse);

        service.evaluateSessionForSummarization("session1");

        verify(session, times(1)).setSummaryBlob("New awesome summary");
        verify(sessionRepository, times(1)).save(session);
    }

    @Test
    void evaluateSession_ChatModelThrowsException_CatchesException() {
        AgentSession session = mock(AgentSession.class);
        lenient().when(session.getSessionId()).thenReturn("session1");

        when(sessionRepository.findById("session1")).thenReturn(Optional.of(session));
        when(settingsService.getSummarizationThresholdTurns(anyInt())).thenReturn(10);
        when(modelResolver.resolveOptimizationModel(null)).thenReturn(mockChatModel);

        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            AgentMessage msg = mock(AgentMessage.class);
            lenient().when(msg.getMessageType()).thenReturn("USER");
            lenient().when(msg.getContent()).thenReturn("message " + i);
            messages.add(msg);
        }
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session1")).thenReturn(messages);

        when(mockChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM Timeout"));

        assertDoesNotThrow(() -> service.evaluateSessionForSummarization("session1"));

        verify(sessionRepository, never()).save(any(AgentSession.class));
    }
}
