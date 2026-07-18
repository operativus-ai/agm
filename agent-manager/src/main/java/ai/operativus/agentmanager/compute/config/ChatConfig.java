package ai.operativus.agentmanager.compute.config;

import ai.operativus.agentmanager.compute.monitoring.FinOpsObservedEmbeddingModel;
import ai.operativus.agentmanager.compute.monitoring.GenAiMetricsAdvisor;
import ai.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import java.util.List;

/**
 * Domain Responsibility: Configures Spring AI core models, including Chat Client and Embedding strategies.
 * State: Stateless (Configuration)
 */
@Configuration
public class ChatConfig {

    /**
     * @summary Configures the primary ChatClient Builder bean.
     * @logic Evaluates available compiled ChatModel beans and implements a provider priority fallback strategy (Google/Gemini -> OpenAI -> Anthropic), returning an injected client. Throws IllegalStateException if no engines are discovered.
     *
     * <p>PROTOTYPE scope is load-bearing, not cosmetic. Spring AI's {@code ChatClient.Builder} is
     * a STATEFUL, MUTABLE builder: {@code defaultSystem(...)} / {@code defaultAdvisors(...)} mutate
     * the shared internal request spec in place. Spring AI's own autoconfigured builder bean is
     * prototype-scoped for exactly this reason. As a singleton, a single shared builder leaked one
     * injector's config into every other — e.g. {@code LlmJudgeScorer.defaultSystem("impartial
     * evaluator...")} bled into {@code LlmRouteSelector}, so router classification calls shipped the
     * judge's grading system prompt (and accumulated stray advisors), corrupting routing decisions.
     * Prototype hands each injection point its own independent builder.
     */
    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ChatClient.Builder chatClientBuilder(List<ChatModel> models) {
        if (models.isEmpty()) {
            throw new IllegalStateException("No ChatModel beans found. Please configure at least one LLM provider in application.properties.");
        }
        
        // Strategy: Prefer Google GenAI/Gemini, then OpenAI, then Anthropic, then first available.
        ChatModel primary = models.stream()
            .filter(this::isGemini)
            .findFirst()
            .or(() -> models.stream().filter(this::isOpenAi).findFirst())
            .orElse(models.get(0));

        return ChatClient.builder(primary);
    }

    /**
     * @summary Configures the primary EmbeddingModel bean required by Vector Stores.
     * @logic Evaluates available embedding providers via a fallback strategy prioritizing Gemini and
     *        OpenAI, then wraps the elected model inside {@link FinOpsObservedEmbeddingModel} before
     *        returning. The wrapper intercepts all embed calls to publish token usage OTel metrics,
     *        convert to USD, and enforce per-session budget ceilings — without AOP proxies.
     */
    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            List<EmbeddingModel> models,
            MeterRegistry meterRegistry,
            LiveValuationEngine valuationEngine,
            GenAiMetricsAdvisor metricsAdvisor) {
        if (models.isEmpty()) {
            return null;
        }

        // Strategy: Prefer Google GenAI (consistent with PgVector), then OpenAI, then any
        // real provider, then the NoOp fallback. NoOp must lose to every real provider
        // since it returns zero-vectors and RAG against it is noise.
        // Exclude FinOpsObservedEmbeddingModel from candidate selection to prevent circular wrapping.
        EmbeddingModel elected = models.stream()
            .filter(m -> !(m instanceof FinOpsObservedEmbeddingModel))
            .filter(this::isGeminiEmbedding)
            .findFirst()
            .or(() -> models.stream()
                .filter(m -> !(m instanceof FinOpsObservedEmbeddingModel))
                .filter(this::isOpenAiEmbedding)
                .findFirst())
            .or(() -> models.stream()
                .filter(m -> !(m instanceof FinOpsObservedEmbeddingModel))
                .filter(m -> !(m instanceof NoOpEmbeddingModel))
                .findFirst())
            .orElseGet(() -> models.stream()
                .filter(m -> !(m instanceof FinOpsObservedEmbeddingModel))
                .findFirst()
                .orElse(null));

        if (elected == null) return null;

        return new FinOpsObservedEmbeddingModel(elected, meterRegistry, valuationEngine, metricsAdvisor);
    }

    private boolean isGemini(ChatModel model) {
        String name = model.getClass().getName();
        return name.contains("Google") || name.contains("Gemini");
    }

    private boolean isOpenAi(ChatModel model) {
        return model.getClass().getName().contains("OpenAi");
    }

    private boolean isGeminiEmbedding(EmbeddingModel model) {
        String name = model.getClass().getName();
        return name.contains("Google") || name.contains("Gemini");
    }

    private boolean isOpenAiEmbedding(EmbeddingModel model) {
        return model.getClass().getName().contains("OpenAi");
    }

    /**
     * @summary Default-fallback embedding model wired when no real provider is
     *     configured. The 2026-05-27 dry-run found that without this, the
     *     production app cannot boot — the Google AI embedding auto-config
     *     requires GCP credentials and there is no graceful default for the
     *     "operator does not use Google" case.
     * @logic Registers a {@link NoOpEmbeddingModel} that emits zero-vectors at
     *     the expected pgvector dimension (768). When a real provider's
     *     embedding model bean is present, the priority cascade in
     *     {@link #primaryEmbeddingModel} elects it (Gemini > OpenAI > first
     *     non-NoOp); the NoOp only wins when nothing else is wired.
     */
    @Bean
    public EmbeddingModel noOpEmbeddingModel() {
        return new NoOpEmbeddingModel();
    }
}

