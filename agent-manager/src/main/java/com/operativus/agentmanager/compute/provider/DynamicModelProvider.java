package com.operativus.agentmanager.compute.provider;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.lang.Nullable;

/**
 * Domain Responsibility: Strategy interface for dynamic LLM Model instantiation, defining a standardized contract for building ChatModels specifically tailored to individual providers.
 * State: Stateless (Interface)
 */
public interface DynamicModelProvider {

    /**
     * @summary Retrieves the strategic provider identifiers.
     * @logic Returns list of canonical provider identifiers mapped to the ModelEntity 'provider' enum string (e.g., "ANTHROPIC", "OPENAI").
     */
    java.util.List<String> getProviderKeys();

    /**
     * @summary Builds an executable Spring AI ChatModel ready for dynamic usage.
     * @logic Strategy implementations must resolve the configuration and construct the correct provider client manually.
     */
    ChatModel buildChatModel(ModelEntity modelEntity, @Nullable AgentDefinition agentDefinition);

    /**
     * @summary Builds an executable Spring AI EmbeddingModel ready for dynamic usage.
     * @logic Throws UnsupportedOperationException by default unless overridden by the implementing strategy.
     */
    org.springframework.ai.embedding.EmbeddingModel buildEmbeddingModel(ModelEntity modelEntity);
}
