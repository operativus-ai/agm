package com.operativus.agentmanager.compute.provider;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Concrete Strategy processing OpenAI APIs (GPT-4o, o3-mini).
 * State: Stateless (Configuration Strategy)
 */
@Component
public class OpenAiModelProvider extends AbstractDynamicModelProvider {

    private final org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider;

    /**
     * Target output dimensionality for embeddings, bound to the pgvector store column so emitted
     * vectors fit. {@code text-embedding-3-*} models support reducing their native dimension via the
     * OpenAI {@code dimensions} request parameter (3-small is 1536 natively, 3-large 3072). Left at 0
     * when the bean is constructed outside Spring (unit tests) — see {@link #buildEmbeddingModel}.
     */
    @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.pgvector.dimension:0}")
    private int embeddingDimensions;

    public OpenAiModelProvider(ProviderCredentialOperations providerCredentials, org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider) {
        super(providerCredentials);
        this.toolCallingManagerProvider = toolCallingManagerProvider;
    }

    /**
     * @summary Retrieves the strategic identifiers mapped to OpenAI providers.
     * @logic Returns 'OPENAI' and 'GPT' mapping keys.
     */
    @Override
    public List<String> getProviderKeys() {
        return List.of("OPENAI", "GPT");
    }

    /**
     * @summary Builds an executable Spring AI OpenAiChatModel ready for dynamic usage.
     * @logic Resolves API credentials securely, builds OpenAiChatOptions, and natively maps the global ToolCallingManager into the constructed ChatGPT model.
     */
    @Override
    public ChatModel buildChatModel(ModelEntity me, @Nullable AgentDefinition def) {
        String modelName = me.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("ModelEntity '" + me.getName() + "' lacks a remote modelName identifier for OpenAi API.");
        }

        String apiKey = resolveApiKey(me);
        String baseUrl = (me.getBaseUrl() != null && !me.getBaseUrl().isBlank()) ? me.getBaseUrl() : defaultBaseUrl();
        OpenAIClient client = buildOpenAiClient(baseUrl, apiKey);
        OpenAIClientAsync asyncClient = buildOpenAiClientAsync(baseUrl, apiKey);

        var builder = OpenAiChatOptions.builder().model(modelName);
        if (me.getMaxOutputTokens() != null) builder.maxCompletionTokens(me.getMaxOutputTokens());

        if (def != null && def.monitoringEnabled() && (modelName.contains("o1") || modelName.contains("o3"))) {
            log.info("Agent {} is using a high-reasoning o-series OpenAI model natively.", def.id());
        }

        // Both sync and async clients are required: OpenAiChatModel's constructor calls
        // setupAsyncClient(...) without credentials if openAiClientAsync is not supplied,
        // which throws IllegalStateException ("`credential` is required, but was not set").
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(asyncClient)
                .options(builder.build())
                .toolCallingManager(this.toolCallingManagerProvider.getObject())
                .build();
    }

    /**
     * @summary Builds an executable Spring AI OpenAiEmbeddingModel.
     * @logic Resolves API credentials securely and constructs the standardized OpenAI embedding API implementation.
     */
    @Override
    public org.springframework.ai.embedding.EmbeddingModel buildEmbeddingModel(ModelEntity me) {
        String modelName = me.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("ModelEntity '" + me.getName() + "' lacks a remote modelName identifier for OpenAi API.");
        }

        String apiKey = resolveApiKey(me);
        String baseUrl = (me.getBaseUrl() != null && !me.getBaseUrl().isBlank()) ? me.getBaseUrl() : defaultBaseUrl();
        OpenAIClient client = buildOpenAiClient(baseUrl, apiKey);
        // The (OpenAIClient)-only ctor falls back to "text-embedding-ada-002" — every embedding
        // call would silently use the default regardless of ModelEntity.modelName. Bind the
        // configured model via explicit options.
        var optionsBuilder = org.springframework.ai.openai.OpenAiEmbeddingOptions.builder()
                .model(modelName);
        // text-embedding-3-* support output-dimension reduction via the `dimensions` request
        // parameter; bind it to the pgvector store dimension so vectors fit the column (3-small is
        // 1536 natively — a DIMENSION_MISMATCH against a 768 store without this). ada-002 has a fixed
        // dimension and rejects the parameter, so scope it to the v3 family. embeddingDimensions == 0
        // means the bean wasn't Spring-injected (unit tests) — skip and emit the model's native size.
        if (embeddingDimensions > 0 && modelName.startsWith("text-embedding-3")) {
            optionsBuilder.dimensions(embeddingDimensions);
        }
        var options = optionsBuilder.build();
        return new org.springframework.ai.openai.OpenAiEmbeddingModel(
                client,
                org.springframework.ai.document.MetadataMode.EMBED,
                options);
    }

    /**
     * @summary The base URL used when a model row doesn't set its own. Overridden by
     *     {@code LiteLlmModelProvider} to point at an OpenAI-compatible LiteLLM proxy.
     */
    protected String defaultBaseUrl() {
        return "https://api.openai.com/v1";
    }

    /**
     * @summary Builds an OpenAI SDK client using the OpenAiSetup helper from Spring AI 2.0.
     * @logic Delegates to the Spring AI setup utility which handles credential wiring and base URL configuration.
     */
    private OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
        return OpenAiSetup.setupSyncClient(
                baseUrl,                    // baseUrl
                apiKey,                     // apiKey
                null,                       // credential (null = use apiKey)
                null,                       // organizationId
                null,                       // azureServiceVersion
                null,                       // userAgent
                false,                      // isAzure
                false,                      // isGithubModels
                null,                       // azureDeploymentName
                java.time.Duration.ofSeconds(60),  // connectTimeout — OpenAi SDK builder rejects null
                3,                          // maxRetries
                null,                       // proxy
                Map.of(),                   // headers
                io.micrometer.observation.ObservationRegistry.NOOP,  // observationRegistry
                null,                       // meterRegistry (helper defaults)
                java.util.List.of()         // httpClientCustomizers — OpenAiSetup iterates this without
                                            // a null guard, so an empty list (not null) means "no customizers"
        );
    }

    private OpenAIClientAsync buildOpenAiClientAsync(String baseUrl, String apiKey) {
        return OpenAiSetup.setupAsyncClient(
                baseUrl,
                apiKey,
                null,                       // credential
                null,                       // organizationId
                null,                       // azureServiceVersion
                null,                       // userAgent
                false,                      // isAzure
                false,                      // isGithubModels
                null,                       // azureDeploymentName
                java.time.Duration.ofSeconds(60),
                3,                          // maxRetries
                null,                       // proxy
                Map.of(),                   // headers
                io.micrometer.observation.ObservationRegistry.NOOP,  // observationRegistry
                null,                       // meterRegistry (helper defaults)
                java.util.List.of()         // httpClientCustomizers — see buildOpenAiClient
        );
    }
}
