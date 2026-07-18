package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ToolCompressionServiceTest {

    @Mock
    private AgentModelResolverService modelResolver;
    
    @Mock
    private SettingsOperations settingsService;
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatModel mockChatModel;

    private ToolCompressionService service;

    @BeforeEach
    void setUp() {
        service = new ToolCompressionService(modelResolver, settingsService);
    }

    @Test
    void compressIfRequired_PayloadNull_ReturnsNull() {
        assertNull(service.compressIfRequired("testTool", null, null));
    }

    @Test
    void compressIfRequired_PayloadBelowThreshold_ReturnsUnmodified() {
        String payload = "short payload";
        when(settingsService.getCompressionThresholdChars(8000)).thenReturn(8000);
        
        String result = service.compressIfRequired("testTool", payload, null);
        
        assertEquals(payload, result);
        verify(modelResolver, never()).resolveOptimizationModel(any());
    }

    @Test
    void compressIfRequired_GlobalThresholdExceeded_ButNoModel_ReturnsUnmodified() {
        String payload = "a".repeat(100);
        when(settingsService.getCompressionThresholdChars(8000)).thenReturn(50);
        when(modelResolver.resolveOptimizationModel(null)).thenReturn(null);
        
        String result = service.compressIfRequired("testTool", payload, null);
        
        assertEquals(payload, result);
        verify(modelResolver, times(1)).resolveOptimizationModel(null);
    }

    @Test
    void compressIfRequired_AgentThresholdExceeded_UsesAgentOptimizationModel_AndCompresses() {
        String payload = "massive payload that exceeds threshold";
        
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.compressionThreshold()).thenReturn(10);
        lenient().when(def.optimizationModelId()).thenReturn("agent-opt-model");
        
        when(modelResolver.resolveOptimizationModel("agent-opt-model")).thenReturn(mockChatModel);
        
        ChatResponse mockResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("extracted data"))));
        when(mockChatModel.call(any(Prompt.class))).thenReturn(mockResponse);

        String result = service.compressIfRequired("testTool", payload, def);

        verify(modelResolver, times(1)).resolveOptimizationModel("agent-opt-model");
        assertTrue(result.contains("extracted data"));
        assertTrue(result.contains("OPTIMIZED DATA PAYLOAD"));
    }

    @Test
    void compressIfRequired_ModelThrowsException_ReturnsFallbackJson() {
        String payload = "massive payload that exceeds threshold";
        
        when(settingsService.getCompressionThresholdChars(8000)).thenReturn(10);
        when(modelResolver.resolveOptimizationModel(null)).thenReturn(mockChatModel);
        
        when(mockChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM Timeout"));

        String result = assertDoesNotThrow(() -> service.compressIfRequired("testTool", payload, null));

        assertTrue(result.contains("error_constraint"));
        assertTrue(result.contains(String.valueOf(payload.length())));
    }
}
