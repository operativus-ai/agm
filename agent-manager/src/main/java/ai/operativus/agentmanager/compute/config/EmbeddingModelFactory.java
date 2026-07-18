package ai.operativus.agentmanager.compute.config;

import ai.operativus.agentmanager.compute.service.AgentModelResolverService;
import ai.operativus.agentmanager.core.registry.SettingsOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Domain Responsibility: Constructs the DB-configured default {@code EmbeddingModel} bean (the one
 *     backing the pgvector store for RAG + agentic memory). Reads the configured embedding model id
 *     from settings and delegates construction to {@link AgentModelResolverService} so ANY
 *     embedding-capable provider (OpenAI, Gemini, Ollama) is supported through the same
 *     DynamicModelProvider SPI as chat models — not just OpenAI. Returns {@code null} when no
 *     embedding model is configured/buildable, letting Spring fall back to an auto-configured or
 *     NoOp {@code EmbeddingModel} (see {@code ChatConfig.primaryEmbeddingModel}).
 * State: Stateless (Configuration / Factory)
 */
@Configuration
public class EmbeddingModelFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelFactory.class);

    /**
     * @summary Instantiates the default {@code EmbeddingModel} from the globally configured model id.
     * @logic Reads {@code DEFAULT_MODEL_EMBEDDING} via {@link SettingsOperations} and builds the model
     *     through {@link AgentModelResolverService#buildEmbeddingModel(String)} (provider-agnostic).
     *     Returns null (→ auto-config/NoOp fallback) when unset or unbuildable.
     */
    @Bean
    public EmbeddingModel defaultEmbeddingModel(SettingsOperations settingsOperations,
                                                AgentModelResolverService modelResolver) {
        String defaultModelId = settingsOperations.getDefaultModelEmbedding();
        log.info("Resolving default embedding model ID from app_settings: {}", defaultModelId);

        if (defaultModelId == null || defaultModelId.isBlank()) {
            log.warn("No default EMBEDDING model configured (settings key DEFAULT_MODEL_EMBEDDING). "
                     + "Semantic search will fall back to auto-config/NoOp (zero-vector) until one is set.");
            return null;
        }

        EmbeddingModel model = modelResolver.buildEmbeddingModel(defaultModelId);
        if (model == null) {
            log.warn("Could not build an EmbeddingModel for configured id '{}' (missing row, unregistered "
                     + "provider, provider without embedding support, or no API key). Falling back to "
                     + "auto-config/NoOp.", defaultModelId);
        } else {
            log.info("Initialized default EmbeddingModel from DB config id '{}' ({})",
                    defaultModelId, model.getClass().getSimpleName());
        }
        return model;
    }
}
