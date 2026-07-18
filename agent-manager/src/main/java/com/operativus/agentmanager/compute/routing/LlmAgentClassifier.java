package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.compute.provider.DynamicModelProvider;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.registry.ModelOperations;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Strategy-2 classifier for the universal-dispatch cascade. Given a
 *     user message and the org's active agent roster, calls the org-configured (or
 *     system-default) LLM to pick the single best-fit agent. Returns an empty Optional on
 *     ANY failure path — null classifier model, model-row missing, provider not registered,
 *     ChatClient throws, decision invalid, confidence below floor, agentId not in
 *     candidate set. Soft failure preserves the cascade: the resolver always continues to
 *     strategy 3 (rule classifier) and fallback.
 *
 *     <p>API key resolution flows through {@code AbstractDynamicModelProvider.resolveApiKey}
 *     (DB-only chain: per-model override → ProviderCredential default for caller orgId or
 *     DEFAULT_SYSTEM_ORG), matching the post-refactor model-build pattern established by
 *     {@code AgentModelResolverService.buildViaDynamicProvider}.
 * State: Stateless (Spring singleton)
 */
@Component
public class LlmAgentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentClassifier.class);

    private static final String PROMPT = """
            You are a semantic routing classifier. Select the single best agent to handle the user's request.
            Respond with JSON ONLY in this shape: {"agentId": "<id>", "confidence": <0.0-1.0>, "rationale": "<short reason>"}
            The agentId MUST be one of the IDs listed below — do not invent agents.

            Available Agents:
            {agents}

            User Request: "{query}"
            """;

    private final ModelOperations modelService;
    private final AgentRepository agentRepository;
    private final Map<String, DynamicModelProvider> dynamicProviders;
    private final String defaultClassifierModelId;
    private final double minConfidence;

    public LlmAgentClassifier(ModelOperations modelService,
                              AgentRepository agentRepository,
                              List<DynamicModelProvider> providers,
                              @Value("${agm.routing.classifier.default-model-id:}") String defaultClassifierModelId,
                              @Value("${agm.routing.classifier.min-confidence:0.6}") double minConfidence) {
        this.modelService = modelService;
        this.agentRepository = agentRepository;
        Map<String, DynamicModelProvider> reg = new HashMap<>();
        for (DynamicModelProvider p : providers) {
            for (String key : p.getProviderKeys()) {
                if (key != null) reg.put(key.toUpperCase(), p);
            }
        }
        this.dynamicProviders = Collections.unmodifiableMap(reg);
        this.defaultClassifierModelId = (defaultClassifierModelId == null || defaultClassifierModelId.isBlank())
                ? null : defaultClassifierModelId;
        this.minConfidence = minConfidence;
    }

    @CircuitBreaker(name = "llm-classifier", fallbackMethod = "classifyFallback")
    public Optional<ClassifierDecision> classify(String orgId,
                                                  String userId,
                                                  String message,
                                                  List<AgentDefinition> candidates,
                                                  String orgClassifierModelId) {
        if (message == null || message.isBlank() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String modelId = (orgClassifierModelId != null && !orgClassifierModelId.isBlank())
                ? orgClassifierModelId : defaultClassifierModelId;
        if (modelId == null) {
            log.debug("LLM classifier requested for org {} but no model configured (org or system default) — skipping",
                    orgId);
            return Optional.empty();
        }
        ChatModel chatModel = buildChatModel(modelId);
        if (chatModel == null) {
            return Optional.empty();
        }
        try {
            Map<String, List<String>> caps = loadCapabilities(orgId, candidates);
            PromptTemplate template = new PromptTemplate(PROMPT);
            template.add("agents", renderAgents(candidates, caps));
            template.add("query", message);
            ClassifierDecision decision = ChatClient.builder(chatModel).build()
                    .prompt(template.create())
                    .call()
                    .entity(ClassifierDecision.class);
            return validate(decision, candidates, orgId);
        } catch (Exception ex) {
            log.warn("LLM classifier call failed for org {}: {}", orgId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resilience4j fallback when the classifier circuit opens (consecutive ChatClient
     * failures, timeouts, etc.). Returns empty so the resolver cascade continues to
     * the next strategy. Throwable arg is required by the @CircuitBreaker contract.
     */
    @SuppressWarnings("unused")
    private Optional<ClassifierDecision> classifyFallback(String orgId,
                                                           String userId,
                                                           String message,
                                                           List<AgentDefinition> candidates,
                                                           String orgClassifierModelId,
                                                           Throwable t) {
        log.warn("LLM classifier circuit-breaker fallback for org {}: {}", orgId,
                t == null ? "open" : t.getMessage());
        return Optional.empty();
    }

    private Map<String, List<String>> loadCapabilities(String orgId, List<AgentDefinition> candidates) {
        Map<String, List<String>> out = new HashMap<>();
        for (AgentDefinition a : candidates) {
            try {
                agentRepository.findByIdAndOrgId(a.id(), orgId)
                        .map(AgentEntity::getCapabilities)
                        .filter(arr -> arr != null && arr.length > 0)
                        .ifPresent(arr -> out.put(a.id(), Arrays.asList(arr)));
            } catch (Exception ignore) {
                // capabilities are advisory — never break the classifier
            }
        }
        return out;
    }

    private ChatModel buildChatModel(String modelId) {
        Optional<ModelEntity> meOpt = modelService.getModelEntityById(modelId);
        if (meOpt.isEmpty()) {
            log.debug("Classifier model id '{}' not found in models table", modelId);
            return null;
        }
        ModelEntity me = meOpt.get();
        if (me.getProvider() == null) return null;
        DynamicModelProvider provider = dynamicProviders.get(me.getProvider().toUpperCase());
        if (provider == null) {
            log.debug("No DynamicModelProvider registered for provider '{}' on classifier model '{}'",
                    me.getProvider(), modelId);
            return null;
        }
        try {
            return provider.buildChatModel(me, null);
        } catch (Exception e) {
            log.warn("Failed to build classifier ChatModel for '{}': {}", modelId, e.getMessage());
            return null;
        }
    }

    private static String renderAgents(List<AgentDefinition> candidates, Map<String, List<String>> capsByAgent) {
        return AgentSelectorPromptTemplate.renderCandidates(candidates, capsByAgent);
    }

    private Optional<ClassifierDecision> validate(ClassifierDecision decision,
                                                   List<AgentDefinition> candidates,
                                                   String orgId) {
        if (decision == null || decision.agentId() == null || decision.confidence() == null) {
            log.debug("Classifier returned malformed decision for org {} — rejecting", orgId);
            return Optional.empty();
        }
        if (decision.confidence() < minConfidence) {
            log.debug("Classifier confidence {} below floor {} for org {} — rejecting",
                    decision.confidence(), minConfidence, orgId);
            return Optional.empty();
        }
        String finalId = decision.agentId().trim();
        if (candidates.stream().noneMatch(a -> a.id().equals(finalId))) {
            log.debug("Classifier returned agentId '{}' not in candidate set for org {} — rejecting",
                    finalId, orgId);
            return Optional.empty();
        }
        return Optional.of(decision);
    }
}
