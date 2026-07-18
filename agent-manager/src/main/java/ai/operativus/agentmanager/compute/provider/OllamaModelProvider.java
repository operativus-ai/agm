package ai.operativus.agentmanager.compute.provider;

import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Domain Responsibility: Concrete Strategy processing Local Ollama models.
 * State: Stateless (Configuration Strategy)
 */
@Component
public class OllamaModelProvider extends AbstractDynamicModelProvider {

    private final org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider;

    public OllamaModelProvider(ProviderCredentialOperations providerCredentials, org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider) {
        super(providerCredentials);
        this.toolCallingManagerProvider = toolCallingManagerProvider;
    }

    /**
     * @summary Retrieves the strategic identifiers mapped to Ollama providers.
     * @logic Returns 'OLLAMA' mapping key.
     */
    @Override
    public List<String> getProviderKeys() {
        return List.of("OLLAMA");
    }

    /**
     * @summary Builds an executable Spring AI OllamaChatModel ready for dynamic usage.
     * @logic Resolves the baseUrl dynamically, builds OllamaChatOptions, and natively maps the global ToolCallingManager into the constructed localized chat model.
     */
    @Override
    public ChatModel buildChatModel(ModelEntity me, @Nullable AgentDefinition def) {
        String modelName = me.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("ModelEntity '" + me.getName() + "' lacks a remote modelName identifier for Ollama.");
        }

        String baseUrl = (me.getBaseUrl() != null && !me.getBaseUrl().isBlank()) ? me.getBaseUrl() : "http://localhost:11434";
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();

        var builder = OllamaChatOptions.builder().model(modelName);

        if (def != null && def.monitoringEnabled()) {
            log.info("Agent {} monitoring enabled on Ollama.", def.id());
        }

        return new OllamaChatModel(api,
                builder.build(),
                this.toolCallingManagerProvider.getObject(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                org.springframework.ai.ollama.management.ModelManagementOptions.builder().build());
    }

    /**
     * @summary Builds an executable Spring AI OllamaEmbeddingModel.
     * @logic Resolves the baseUrl dynamically and configures standard OllamaEmbeddingModel instances.
     */
    @Override
    public org.springframework.ai.embedding.EmbeddingModel buildEmbeddingModel(ModelEntity me) {
        String modelName = me.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("ModelEntity '" + me.getName() + "' lacks a remote modelName identifier for Ollama.");
        }

        String baseUrl = (me.getBaseUrl() != null && !me.getBaseUrl().isBlank()) ? me.getBaseUrl() : "http://localhost:11434";
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();

        return org.springframework.ai.ollama.OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                // Spring AI 2.0.0-SNAPSHOT renamed Builder.defaultOptions(...) → options(...).
                .options(org.springframework.ai.ollama.api.OllamaEmbeddingOptions.builder().model(modelName).build())
                .build();
    }
}
