package com.operativus.agentmanager.compute.provider;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Domain Responsibility: Registers the {@code "ECHO"} provider so {@code models} rows with
 * {@code provider='ECHO'} resolve to a keyless {@link EchoChatModel}. Collected into
 * {@code AgentClientFactory.modelProviderRegistry} like every other {@link DynamicModelProvider}.
 *
 * <p>Unlike {@link AbstractDynamicModelProvider} subclasses, this provider performs NO API-key
 * resolution — the echo model needs no credential — so demo/offline agents bound to an ECHO
 * model run without any provider configuration. {@code DynamicProviderInitializer} only gates
 * OpenAI/Anthropic/Google, so ECHO is always available.</p>
 *
 * State: Stateless.
 */
@Component
public class EchoModelProvider implements DynamicModelProvider {

    @Override
    public List<String> getProviderKeys() {
        return List.of("ECHO");
    }

    @Override
    public ChatModel buildChatModel(ModelEntity modelEntity, @Nullable AgentDefinition agentDefinition) {
        // Prefer the agent's name so a multi-step workflow's threaded payload shows which step
        // produced each line; fall back to the model name, then a generic label.
        String label = "echo";
        if (agentDefinition != null && agentDefinition.name() != null && !agentDefinition.name().isBlank()) {
            label = agentDefinition.name();
        } else if (modelEntity != null && modelEntity.getModelName() != null) {
            label = modelEntity.getModelName();
        }
        return new EchoChatModel(label);
    }

    @Override
    public org.springframework.ai.embedding.EmbeddingModel buildEmbeddingModel(ModelEntity modelEntity) {
        throw new UnsupportedOperationException("EchoModelProvider does not provide embeddings");
    }
}
