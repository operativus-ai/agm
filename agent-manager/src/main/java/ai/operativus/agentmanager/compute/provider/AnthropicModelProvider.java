package ai.operativus.agentmanager.compute.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Concrete Strategy processing Claude/Anthropic APIs. Configures Claude 3.5 Sonnet / Haiku execution behavior, including specific <think> budget injection for System 2 reasoning.
 * State: Stateless (Configuration Strategy)
 */
@Component
public class AnthropicModelProvider extends AbstractDynamicModelProvider {

    private final org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider;

    public AnthropicModelProvider(ProviderCredentialOperations providerCredentials, org.springframework.beans.factory.ObjectProvider<org.springframework.ai.model.tool.ToolCallingManager> toolCallingManagerProvider) {
        super(providerCredentials);
        this.toolCallingManagerProvider = toolCallingManagerProvider;
    }

    /**
     * @summary Retrieves the strategic identifiers mapped to Anthropic providers.
     * @logic Returns 'ANTHROPIC' and 'CLAUDE' mapping keys.
     */
    @Override
    public List<String> getProviderKeys() {
        return List.of("ANTHROPIC", "CLAUDE");
    }

    /**
     * @summary Builds an executable Spring AI AnthropicChatModel ready for dynamic usage.
     * @logic Resolves API credentials securely, builds AnthropicChatOptions with required thinking budget constraints, and natively maps the global ToolCallingManager into the constructed ChatModel.
     */
    @Override
    public ChatModel buildChatModel(ModelEntity me, @Nullable AgentDefinition def) {
        String modelName = me.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("ModelEntity '" + me.getName() + "' lacks a remote modelName identifier for Anthropic API.");
        }

        String apiKey = resolveApiKey(me);
        String baseUrl = (me.getBaseUrl() != null && !me.getBaseUrl().isBlank()) ? me.getBaseUrl() : "https://api.anthropic.com";

        var builder = AnthropicChatOptions.builder()
                .model(modelName)
                .apiKey(apiKey)
                .baseUrl(baseUrl);

        if (me.getMaxOutputTokens() != null) builder.maxTokens(me.getMaxOutputTokens());

        // PHASE 5: DYNAMIC REASONING
        if (def != null && def.monitoringEnabled()) {
            int thinkTokens = (me.getThinkingBudgetTokens() != null && me.getThinkingBudgetTokens() > 0) ? me.getThinkingBudgetTokens() : 2048;
            log.info("Agent {} has reasoning enabled. Injecting Anthropic Thinking budget: {} tokens", def.id(), thinkTokens);
            // builder.thinking(AnthropicApi.ThinkingType.ENABLED, thinkTokens); // Removed until specific ENUM is resolved.
        } else if (me.getThinkingBudgetTokens() != null && me.getThinkingBudgetTokens() > 0) {
            // builder.thinking(AnthropicApi.ThinkingType.ENABLED, me.getThinkingBudgetTokens());
        }

        // Both sync and async clients must be supplied: AnthropicChatModel's constructor
        // lazy-builds clients via AnthropicSetup without seeing the options.baseUrl, so
        // requests would route to https://api.anthropic.com regardless of the ModelEntity
        // base_url field. Building both clients explicitly here also closes the door on the
        // OpenAi-style "`credential` is required" trap.
        AnthropicClient syncClient = AnthropicSetup.setupSyncClient(
                baseUrl, apiKey, Duration.ofSeconds(60), 3, null, Map.of());
        AnthropicClientAsync asyncClient = AnthropicSetup.setupAsyncClient(
                baseUrl, apiKey, Duration.ofSeconds(60), 3, null, Map.of());

        return AnthropicChatModel.builder()
                .anthropicClient(syncClient)
                .anthropicClientAsync(asyncClient)
                .options(builder.build())
                .toolCallingManager(this.toolCallingManagerProvider.getObject())
                .build();
    }
}
