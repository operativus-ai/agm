package ai.operativus.agentmanager.compute.provider;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Domain Responsibility: Concrete Strategy processing Google Gemini APIs.
 * State: Stateless (Configuration Strategy)
 */
@Component
public class GeminiModelProvider extends AbstractDynamicModelProvider {

    private final org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider;

    public GeminiModelProvider(ProviderCredentialOperations providerCredentials, org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider) {
        super(providerCredentials);
        this.toolCallingManagerProvider = toolCallingManagerProvider;
    }

    /**
     * @summary Retrieves the strategic identifiers mapped to Gemini providers.
     * @logic Returns 'GOOGLE' and 'GEMINI' mapping keys.
     */
    @Override
    public List<String> getProviderKeys() {
        return List.of("GOOGLE", "GEMINI");
    }

    /**
     * @summary Builds an executable Spring AI GoogleGenAiChatModel ready for dynamic usage.
     * @logic Resolves API credentials securely, builds GoogleGenAiChatOptions, and natively maps the global ToolCallingManager into the constructed chat model.
     */
    @Override
    public ChatModel buildChatModel(ModelEntity me, @Nullable AgentDefinition def) {
        String modelName = me.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("ModelEntity '" + me.getName() + "' lacks a remote modelName identifier for Gemini API.");
        }

        String apiKey = resolveApiKey(me);

        // ModelEntity.base_url was previously ignored: Client.builder().apiKey(apiKey).build()
        // always routes to https://generativelanguage.googleapis.com regardless of the DB
        // column. Threading the resolved baseUrl through HttpOptions makes the field do what
        // its name implies and unblocks self-hosted Gemini-compatible endpoints.
        Client.Builder clientBuilder = Client.builder().apiKey(apiKey);
        if (me.getBaseUrl() != null && !me.getBaseUrl().isBlank()) {
            clientBuilder.httpOptions(HttpOptions.builder().baseUrl(me.getBaseUrl()).build());
        }
        Client genAiClient = clientBuilder.build();

        var builder = GoogleGenAiChatOptions.builder().model(modelName);
        if (me.getMaxOutputTokens() != null) builder.maxOutputTokens(me.getMaxOutputTokens());

        if (def != null && def.monitoringEnabled()) {
            log.info("Agent {} monitoring enabled on Gemini.", def.id());
        }

        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                // Spring AI 2.0.0-SNAPSHOT renamed Builder.defaultOptions(...) → options(...).
                .options(builder.build())
                .toolCallingManager(this.toolCallingManagerProvider.getObject())
                .build();
    }

    /**
     * @summary Builds an executable Spring AI GoogleGenAiTextEmbeddingModel.
     * @logic Resolves the Google API key, constructs the GenAI Client (threading
     *   ModelEntity.baseUrl through HttpOptions to mirror the chat path), and binds the
     *   configured modelName via GoogleGenAiTextEmbeddingOptions. Without an explicit options
     *   binding, the embedding call would fall back to the SDK default model.
     */
    @Override
    public EmbeddingModel buildEmbeddingModel(ModelEntity me) {
        String modelName = me.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("ModelEntity '" + me.getName() + "' lacks a remote modelName identifier for Gemini embedding API.");
        }

        String apiKey = resolveApiKey(me);

        Client.Builder clientBuilder = Client.builder().apiKey(apiKey);
        if (me.getBaseUrl() != null && !me.getBaseUrl().isBlank()) {
            clientBuilder.httpOptions(HttpOptions.builder().baseUrl(me.getBaseUrl()).build());
        }
        Client genAiClient = clientBuilder.build();

        var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(apiKey)
                .genAiClient(genAiClient)
                .build();
        var options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(modelName)
                .build();
        return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
    }
}
