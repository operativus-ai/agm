package com.operativus.agentmanager.integration.support;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Domain Responsibility: Wires a single {@link FakeEmbeddingModel} into the test
 *   ApplicationContext so production code that depends on the Spring AI
 *   {@link EmbeddingModel} boundary (knowledge-base ingestion, agentic memory,
 *   pgvector cache store, semantic scorer) executes end-to-end without ever
 *   leaving the JVM. Imported per-test via
 *   {@code @Import(FakeEmbeddingModelConfig.class)} — never auto-applied.
 * State: Stateless (the {@link FakeEmbeddingModel} bean carries the recorded-request log).
 *
 * Wiring strategy: Google GenAI embedding auto-configs are excluded in
 *   {@code application-test.properties} (they'd otherwise create a Vertex AI
 *   embedding bean that 403s under test credentials). OpenAI and Anthropic
 *   embeddings are disabled via {@code spring.ai.*.embedding.enabled=false} in
 *   {@code application.properties}. So the fake is the ONLY {@link EmbeddingModel}
 *   in the list that {@code ChatConfig.primaryEmbeddingModel} scans — it gets
 *   elected and wrapped in {@code FinOpsObservedEmbeddingModel}, which becomes
 *   the {@code @Primary} bean used by {@code VectorStoreConfig.cacheVectorStore}
 *   and every other {@code EmbeddingModel} injection point.
 *
 *   We deliberately do NOT mark this bean {@code @Primary}: adding a second
 *   primary of the same interface would collide with
 *   {@code ChatConfig.primaryEmbeddingModel}. Tests that need to assert on
 *   received embedding requests inject {@link FakeEmbeddingModel} by concrete
 *   type — unambiguous since it's the only instance in the context.
 */
@TestConfiguration
public class FakeEmbeddingModelConfig {

    @Bean
    public FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }
}
