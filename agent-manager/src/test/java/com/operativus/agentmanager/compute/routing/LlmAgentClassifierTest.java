package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.compute.provider.DynamicModelProvider;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.ComplianceTier;
import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.registry.ModelOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LlmAgentClassifierTest {

    @Mock private ModelOperations modelService;
    @Mock private AgentRepository agentRepository;
    @Mock private DynamicModelProvider openAiProvider;

    private LlmAgentClassifier classifier;

    @BeforeEach
    void setUp() {
        when(openAiProvider.getProviderKeys()).thenReturn(List.of("OPENAI"));
        classifier = new LlmAgentClassifier(modelService, agentRepository, List.of(openAiProvider), "", 0.6);
    }

    private static AgentDefinition agent(String id) {
        return new AgentDefinition(
                id, id, "desc", "instructions", "model-x",
                null, null, null, null,
                false, false, null, null,
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void classify_blankMessage_returnsEmpty() {
        Optional<ClassifierDecision> result = classifier.classify(
                "ORG", "u", "  ", List.of(agent("a")), "model-1");
        assertTrue(result.isEmpty());
        verifyNoInteractions(modelService);
    }

    @Test
    void classify_emptyCandidates_returnsEmpty() {
        Optional<ClassifierDecision> result = classifier.classify(
                "ORG", "u", "hello", List.of(), "model-1");
        assertTrue(result.isEmpty());
        verifyNoInteractions(modelService);
    }

    @Test
    void classify_noClassifierModelConfiguredAndNoSystemDefault_returnsEmpty() {
        Optional<ClassifierDecision> result = classifier.classify(
                "ORG", "u", "hello", List.of(agent("a")), null);
        assertTrue(result.isEmpty());
        verifyNoInteractions(modelService);
    }

    @Test
    void classify_modelEntityMissing_returnsEmpty() {
        when(modelService.getModelEntityById("ghost-model")).thenReturn(Optional.empty());

        Optional<ClassifierDecision> result = classifier.classify(
                "ORG", "u", "hello", List.of(agent("a")), "ghost-model");
        assertTrue(result.isEmpty());
    }

    @Test
    void classify_providerNotRegistered_returnsEmpty() {
        ModelEntity me = new ModelEntity();
        me.setId("model-1");
        me.setProvider("UNKNOWN_VENDOR");
        when(modelService.getModelEntityById("model-1")).thenReturn(Optional.of(me));

        Optional<ClassifierDecision> result = classifier.classify(
                "ORG", "u", "hello", List.of(agent("a")), "model-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void classify_providerBuildThrows_returnsEmpty() {
        ModelEntity me = new ModelEntity();
        me.setId("model-1");
        me.setProvider("OPENAI");
        when(modelService.getModelEntityById("model-1")).thenReturn(Optional.of(me));
        when(openAiProvider.buildChatModel(eq(me), any())).thenThrow(new RuntimeException("no API key"));

        Optional<ClassifierDecision> result = classifier.classify(
                "ORG", "u", "hello", List.of(agent("a")), "model-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void classify_providerReturnsNullChatModel_returnsEmpty() {
        ModelEntity me = new ModelEntity();
        me.setId("model-1");
        me.setProvider("OPENAI");
        when(modelService.getModelEntityById("model-1")).thenReturn(Optional.of(me));
        when(openAiProvider.buildChatModel(eq(me), any())).thenReturn(null);

        Optional<ClassifierDecision> result = classifier.classify(
                "ORG", "u", "hello", List.of(agent("a")), "model-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void classify_systemDefaultUsedWhenOrgModelIsBlank() {
        LlmAgentClassifier withDefault = new LlmAgentClassifier(
                modelService, agentRepository, List.of(openAiProvider), "system-default-model", 0.6);
        when(modelService.getModelEntityById("system-default-model")).thenReturn(Optional.empty());

        Optional<ClassifierDecision> result = withDefault.classify(
                "ORG", "u", "hello", List.of(agent("a")), null);

        assertTrue(result.isEmpty(), "Empty when model not found, but the path was exercised");
        verify(modelService).getModelEntityById("system-default-model");
    }
}
