package com.operativus.agentmanager.config;

import com.operativus.agentmanager.compute.security.DeterministicNEREngine;
import com.operativus.agentmanager.compute.security.FormatPreservingEncryptionService;
import com.operativus.agentmanager.compute.security.PiiPolicyService;
import com.operativus.agentmanager.compute.security.PiiRedactingVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Domain Responsibility: Configures the PII-redacting Decorator to wrap the primary auto-configured
 * {@link VectorStore} bean. All application components that inject {@code VectorStore} without
 * a qualifier will receive the redacting decorator, ensuring zero raw PII reaches persistent storage.
 *
 * <p>The inner {@code pgVectorStore} bean is auto-configured by Spring AI's PgVector auto-configuration
 * and is passed here as the delegate.</p>
 *
 * State: Stateless (Configuration)
 */
@Configuration
public class PiiVectorStoreConfig {

    /**
     * @summary Wraps the auto-configured PgVectorStore with the PII-redacting decorator.
     * @logic The {@code @Primary} annotation ensures this bean is injected everywhere a
     *        non-qualified {@code VectorStore} is requested, while the raw inner store
     *        remains available for internal use via the {@code "pgVectorStore"} qualifier.
     */
    @Bean
    @Primary
    public VectorStore piiRedactingVectorStore(
            @Qualifier("vectorStore") VectorStore innerVectorStore,
            DeterministicNEREngine nerEngine,
            FormatPreservingEncryptionService fpeService,
            PiiPolicyService policyService) {
        return new PiiRedactingVectorStore(innerVectorStore, nerEngine, fpeService, policyService);
    }
}
