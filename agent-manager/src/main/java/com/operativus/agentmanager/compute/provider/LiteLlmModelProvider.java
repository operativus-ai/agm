package com.operativus.agentmanager.compute.provider;

import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Domain Responsibility: Provider strategy for a LiteLLM gateway (REQ — provider breadth). LiteLLM
 *     exposes an OpenAI-compatible {@code /v1} API in front of 100+ models (Anthropic, Google, AWS
 *     Bedrock, Azure, Mistral, Cohere, local, …), so this is exactly the {@link OpenAiModelProvider}
 *     client pointed at the LiteLLM proxy instead of {@code api.openai.com}. A model row with
 *     {@code provider=LITELLM} and {@code modelName=<whatever LiteLLM routes>} (e.g.
 *     {@code "claude-3-5-sonnet"}, {@code "bedrock/anthropic.claude-3"}) reaches that model through
 *     AGM's full advisor / FinOps / HITL / multi-tenant pipeline like any other provider.
 *
 *     <p>The proxy URL comes from {@code agm.providers.litellm.base-url}; a per-model {@code baseUrl}
 *     override still wins (multiple LiteLLM instances). The API key resolves DB-only (per-model
 *     override or the {@code (org, LITELLM)} provider credential — typically the LiteLLM master key),
 *     same as every other provider. Not gated by {@code DynamicProviderInitializer} (only OpenAI/
 *     Anthropic/Google are), so it is active whenever a LITELLM model + key exist.
 * State: Stateless (Configuration Strategy).
 */
@Component
public class LiteLlmModelProvider extends OpenAiModelProvider {

    private final String litellmBaseUrl;

    public LiteLlmModelProvider(ProviderCredentialOperations providerCredentials,
                                ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
                                @Value("${agm.providers.litellm.base-url:http://localhost:4000}") String litellmBaseUrl) {
        super(providerCredentials, toolCallingManagerProvider);
        this.litellmBaseUrl = litellmBaseUrl;
    }

    @Override
    public List<String> getProviderKeys() {
        return List.of("LITELLM");
    }

    @Override
    protected String defaultBaseUrl() {
        return litellmBaseUrl;
    }
}
