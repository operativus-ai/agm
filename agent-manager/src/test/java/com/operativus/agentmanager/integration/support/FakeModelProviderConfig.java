package com.operativus.agentmanager.integration.support;

import com.operativus.agentmanager.compute.provider.DynamicModelProvider;
import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Domain Responsibility: Registers a test-only {@link DynamicModelProvider} under the
 *   provider key {@code "FAKE"} so that tests which seed a {@code models} row with
 *   {@code provider='fake'} and then hit the run endpoint flow through
 *   {@link com.operativus.agentmanager.compute.service.AgentClientFactory#instantiateCustomChatModel}
 *   and receive a {@link FakeChatModel} back instead of tripping
 *   {@code "Integration Error: No Provider registered for dynamic instantiation of: fake"}.
 *   Import alongside {@link FakeChatModelConfig} whenever a test drives an end-to-end run
 *   against an agent whose modelId is registered in the {@code models} table.
 * State: Stateless (delegates to the singleton {@link FakeChatModel} bean).
 *
 * Why this is its own @TestConfiguration rather than part of FakeChatModelConfig:
 *   Existing tests that only CRUD agents (T011–T014) seed {@code provider='fake'} but never
 *   reach the {@code instantiateCustomChatModel} switch, so they do not need this provider
 *   registration. Keeping it opt-in preserves the blast radius of changes to the run path.
 *
 * Why the provider key is {@code "FAKE"} (uppercase):
 *   {@code AgentClientFactory} uppercases the ModelEntity's provider string before the
 *   registry lookup, and it also uppercases DynamicModelProvider.getProviderKeys() at
 *   registration time. We return {@code "FAKE"} so both sides match cleanly; the lookup is
 *   still via {@code "fake".toUpperCase()} on the DB value.
 */
@TestConfiguration
public class FakeModelProviderConfig {

    @Bean
    public DynamicModelProvider fakeDynamicModelProvider(FakeChatModel fakeChatModel) {
        return new DynamicModelProvider() {
            @Override
            public List<String> getProviderKeys() {
                return List.of("FAKE");
            }

            @Override
            public ChatModel buildChatModel(ModelEntity modelEntity, @Nullable AgentDefinition agentDefinition) {
                return fakeChatModel;
            }

            @Override
            public EmbeddingModel buildEmbeddingModel(ModelEntity modelEntity) {
                throw new UnsupportedOperationException(
                        "FakeDynamicModelProvider does not expose embeddings; tests that need embedding behavior must wire their own fixture");
            }
        };
    }
}
