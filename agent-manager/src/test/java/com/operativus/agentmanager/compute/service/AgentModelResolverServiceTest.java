package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.registry.ModelOperations;
import com.operativus.agentmanager.core.registry.SettingsOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentModelResolverServiceTest {

    @Mock
    private SettingsOperations settingsService;

    @Mock
    private ModelOperations modelService;

    @Mock
    private Environment environment;

    @Mock
    private ChatModel openAiChatModel;

    @Mock
    private ChatModel googleGenAiChatModel;

    @Mock
    private com.operativus.agentmanager.compute.provider.DynamicModelProvider openAiProvider;

    @Mock
    private com.operativus.agentmanager.compute.provider.DynamicModelProvider googleProvider;

    private AgentModelResolverService service;

    @BeforeEach
    void setUp() {
        lenient().when(openAiProvider.getProviderKeys()).thenReturn(java.util.List.of("OPENAI"));
        lenient().when(googleProvider.getProviderKeys()).thenReturn(java.util.List.of("GOOGLE"));

        service = new AgentModelResolverService(settingsService, modelService, environment,
                java.util.List.of(openAiProvider, googleProvider));
    }

    @Test
    void resolveModel_ExplicitModelIdExists_ReturnsCustomModel() {
        AgentDefinition def = mock(AgentDefinition.class);
        when(def.modelId()).thenReturn("custom-model-id");

        ModelEntity entity = new ModelEntity();
        entity.setId("custom-model-id");
        entity.setModelName("gemini-1.5-pro");

        when(modelService.getModelEntityById("custom-model-id")).thenReturn(Optional.of(entity));

        AgentModelResolverService.ResolvedModel result = service.resolveModel(def);

        assertTrue(result.hasCustomModel());
        assertEquals("gemini-1.5-pro", result.effectiveModelId());
        assertEquals("explicit-agent-config", result.resolvedVia());
    }

    // ── buildEmbeddingModel: provider-agnostic embedding resolution ──────────────

    @Test
    void buildEmbeddingModel_delegatesToRegisteredProvider_returnsModel() {
        ModelEntity emb = new ModelEntity();
        emb.setId("emb-google");
        emb.setProvider("GOOGLE");                  // resolves to googleProvider (case-insensitive)
        emb.setModelName("text-embedding-004");
        org.springframework.ai.embedding.EmbeddingModel built =
                mock(org.springframework.ai.embedding.EmbeddingModel.class);
        when(modelService.getModelEntityById("emb-google")).thenReturn(Optional.of(emb));
        when(googleProvider.buildEmbeddingModel(emb)).thenReturn(built);

        // Not OpenAI-only: a Google embedding model is built via the same SPI.
        assertSame(built, service.buildEmbeddingModel("emb-google"));
    }

    @Test
    void buildEmbeddingModel_unregisteredProvider_returnsNull() {
        ModelEntity emb = new ModelEntity();
        emb.setId("emb-anthropic");
        emb.setProvider("ANTHROPIC");               // no provider registered for ANTHROPIC
        when(modelService.getModelEntityById("emb-anthropic")).thenReturn(Optional.of(emb));

        assertNull(service.buildEmbeddingModel("emb-anthropic"));
    }

    @Test
    void buildEmbeddingModel_providerWithoutEmbeddingSupport_returnsNull() {
        ModelEntity emb = new ModelEntity();
        emb.setId("emb-openai");
        emb.setProvider("OpenAI");                  // registered, but provider rejects embeddings
        emb.setModelName("gpt-5.2");
        when(modelService.getModelEntityById("emb-openai")).thenReturn(Optional.of(emb));
        when(openAiProvider.buildEmbeddingModel(emb))
                .thenThrow(new UnsupportedOperationException("no embeddings"));

        assertNull(service.buildEmbeddingModel("emb-openai"));
    }

    @Test
    void buildEmbeddingModel_blankOrUnknownId_returnsNull() {
        assertNull(service.buildEmbeddingModel(null));
        assertNull(service.buildEmbeddingModel("  "));
        when(modelService.getModelEntityById("missing")).thenReturn(Optional.empty());
        assertNull(service.buildEmbeddingModel("missing"));
    }

    @Test
    void resolveModel_ExplicitModelIdMissing_ReturnsInlineModel() {
        AgentDefinition def = mock(AgentDefinition.class);
        when(def.modelId()).thenReturn("inline-model:latest");

        when(modelService.getModelEntityById("inline-model:latest")).thenReturn(Optional.empty());

        AgentModelResolverService.ResolvedModel result = service.resolveModel(def);

        assertFalse(result.hasCustomModel());
        assertEquals("inline-model:latest", result.effectiveModelId());
        assertEquals("inline-model-id", result.resolvedVia());
    }

    @Test
    void resolveModel_DefaultRouter_UsesSettings() {
        AgentDefinition def = mock(AgentDefinition.class);
        when(def.modelId()).thenReturn(null);
        when(def.isTeam()).thenReturn(true);
        when(def.teamMode()).thenReturn("ROUTER");

        when(settingsService.getDefaultModelRouter()).thenReturn("router-default");
        when(modelService.getModelEntityById("router-default")).thenReturn(Optional.empty());

        AgentModelResolverService.ResolvedModel result = service.resolveModel(def);

        assertEquals("router-default", result.effectiveModelId());
        assertEquals("default-router (teamMode=ROUTER)", result.resolvedVia());
    }

    @Test
    void resolveModel_DefaultHeavy_UsesSettings() {
        AgentDefinition def = mock(AgentDefinition.class);
        when(def.modelId()).thenReturn(null);
        when(def.isTeam()).thenReturn(false); // standard agent

        when(settingsService.getDefaultModelHeavy()).thenReturn("heavy-default");
        when(modelService.getModelEntityById("heavy-default")).thenReturn(Optional.empty());

        AgentModelResolverService.ResolvedModel result = service.resolveModel(def);

        assertEquals("heavy-default", result.effectiveModelId());
        assertEquals("default-heavy", result.resolvedVia());
    }

    @Test
    void validateCapabilities_NoCustomModel_DoesNotThrow() {
        AgentDefinition def = mock(AgentDefinition.class);
        AgentModelResolverService.ResolvedModel resolved = new AgentModelResolverService.ResolvedModel(null, "inline", "inline");

        assertDoesNotThrow(() -> service.validateCapabilities(def, resolved));
    }

    @Test
    void validateCapabilities_CustomModel_MissingToolsSupport_ThrowsException() {
        AgentDefinition def = mock(AgentDefinition.class);
        when(def.tools()).thenReturn(List.of("tool1"));

        ModelEntity me = new ModelEntity();
        me.setSupportsTools(false);
        me.setName("DumbModel");

        AgentModelResolverService.ResolvedModel resolved = new AgentModelResolverService.ResolvedModel(me, "ext-dumb", "explicit");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.validateCapabilities(def, resolved));
        assertTrue(ex.getMessage().contains("does not support tool calling"));
    }

    @Test
    void resolveFastRoutingModel_UsesOpenAI_IfConfigured() {
        ModelEntity me = new ModelEntity();
        me.setId("gpt-4o-mini");
        me.setProvider("OPENAI");
        when(settingsService.getDefaultModelFast()).thenReturn("gpt-4o-mini");
        when(modelService.getModelEntityById("gpt-4o-mini")).thenReturn(Optional.of(me));
        when(openAiProvider.buildChatModel(me, null)).thenReturn(openAiChatModel);

        ChatModel result = service.resolveFastRoutingModel();

        assertSame(openAiChatModel, result);
    }

    @Test
    void resolveFastRoutingModel_UsesGemini_IfConfigured() {
        ModelEntity me = new ModelEntity();
        me.setId("gemini-1.5-flash");
        me.setProvider("GOOGLE");
        when(settingsService.getDefaultModelFast()).thenReturn("gemini-1.5-flash");
        when(modelService.getModelEntityById("gemini-1.5-flash")).thenReturn(Optional.of(me));
        when(googleProvider.buildChatModel(me, null)).thenReturn(googleGenAiChatModel);

        ChatModel result = service.resolveFastRoutingModel();

        assertSame(googleGenAiChatModel, result);
    }

    @Test
    void resolveOptimizationModel_FallsBackToFastRouting_IfNull() {
        ModelEntity me = new ModelEntity();
        me.setId("gpt-4o-mini");
        me.setProvider("OPENAI");
        when(settingsService.getDefaultModelFast()).thenReturn("gpt-4o-mini");
        when(modelService.getModelEntityById("gpt-4o-mini")).thenReturn(Optional.of(me));
        when(openAiProvider.buildChatModel(me, null)).thenReturn(openAiChatModel);

        ChatModel result = service.resolveOptimizationModel(null);

        assertSame(openAiChatModel, result);
    }

    @Test
    void resolveFastRoutingModel_ReturnsNull_WhenNoModelEntity() {
        // Pins the new contract: when the DB path can't build the model, return null
        // rather than falling back to the dummy-keyed auto-config beans.
        when(settingsService.getDefaultModelFast()).thenReturn("nonexistent-model");
        when(modelService.getModelEntityById("nonexistent-model")).thenReturn(Optional.empty());

        assertNull(service.resolveFastRoutingModel());
    }
}
