package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetricConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Domain Responsibility: Intercepts LLM requests and checks the isolated vector_cache for semantic
 * similarities. If a highly similar response is found ("Semantic Cache Hit"), it short-circuits the
 * LLM call and returns the cached insight immediately.
 *
 * FinOps Extension (Phase 2):
 * - On cache MISS: extracts the exact token count from the LLM response metadata and stores it in
 *   the cached Document's metadata map under "cachedTokenCount". This avoids estimation bias on cache hits.
 * - On cache HIT: retrieves the original token count from Document metadata, converts to USD via
 *   LiveValuationEngine, and publishes a {@code finops.cache.savings.usd} Micrometer counter.
 *   String-based token estimations are explicitly forbidden — only stored metadata counts are used.
 *
 * Architecture:
 * - Constructor Injection. No field injection.
 * - No string-based token estimation. Token counts must originate from the provider's Usage metadata.
 */
@Component
public class VectorStoreCacheAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreCacheAdvisor.class);

    /** Metadata key storing the exact prompt token count captured on cache miss. */
    private static final String META_CACHED_TOKEN_COUNT  = "cachedTokenCount";
    /** Metadata key storing the model ID captured on cache miss (for rate lookup on hit). */
    private static final String META_CACHED_MODEL_ID     = "cachedModelId";
    /** Metadata key carrying the writer's org for tenant-scoped cache lookup (audit F17). */
    private static final String META_ORG_ID              = "org_id";

    private static final String METRIC_CACHE_SAVINGS_USD = "finops.cache.savings.usd";

    private final VectorStore cacheVectorStore;
    private final MeterRegistry meterRegistry;
    private final LiveValuationEngine valuationEngine;
    private final ModerationService moderationService;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer, tag {@code advisor=vector_store_cache}. */
    private final Timer durationTimer;

    public VectorStoreCacheAdvisor(
            @Qualifier("cacheVectorStore") VectorStore cacheVectorStore,
            MeterRegistry meterRegistry,
            LiveValuationEngine valuationEngine,
            ModerationService moderationService) {
        this.cacheVectorStore   = cacheVectorStore;
        this.meterRegistry      = meterRegistry;
        this.valuationEngine    = valuationEngine;
        this.moderationService  = moderationService;
        this.durationTimer = Timer.builder(MetricConstants.ADVISOR_DURATION_MS)
                .tag("advisor", "vector_store_cache").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "VectorStoreCacheAdvisor";
    }

    /**
     * @summary Order 15 — runs after PII redaction (order 10) and before content safety (order 20).
     * @logic Per audit F11: this advisor previously ran at order 5, before
     *        PIIAnonymizationAdvisor's order 10, which meant raw user prompts
     *        were sent to the embedding API for cache lookup AND persisted into
     *        vector_cache.content. Moving past the PII boundary makes the cache
     *        key the *redacted* prompt; raw PII never reaches the cache or the
     *        embedding API. Existing PII-bearing rows are purged by Liquibase
     *        changeset 053.
     */
    @Override
    public int getOrder() {
        return 15;
    }

    /**
     * @summary Searches the semantic vector cache for a near-identical prior response,
     *          scoped to the caller's org via FilterExpression on metadata.org_id.
     * @returns Cached Document if found above the 0.95 similarity threshold, null otherwise.
     *          Returns null without a network call when {@code orgId} is not bound — the
     *          cache is intentionally tenant-scoped (audit F17) and an unbound caller
     *          cannot share or read another tenant's cache.
     */
    private Document getCachedDocumentOrNull(String promptText, String orgId) {
        if (promptText == null || promptText.isBlank()) return null;
        if (orgId == null) return null;
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                .query(promptText)
                .topK(1)
                .similarityThreshold(0.95d)
                .filterExpression(new FilterExpressionBuilder().eq(META_ORG_ID, orgId).build())
                .build();
            List<Document> docs = cacheVectorStore.similaritySearch(searchRequest);
            if (docs != null && !docs.isEmpty()) {
                return docs.get(0);
            }
        } catch (com.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException ex) {
            // Budget exhaustion is a hard stop — must propagate so AgentService /
            // A2ATaskExecutor can route to PAUSED / BUDGET_HALT, not be silently
            // treated as a cache miss and let the chat call proceed (which would
            // either also throw or — for non-billable fakes — complete normally
            // with the cumulative already over ceiling).
            throw ex;
        } catch (Exception e) {
            log.warn("Vector_cache lookup failed (orgId={})", orgId, e);
        }
        return null;
    }

    /**
     * @summary Stores the prompt+response pair in the semantic vector cache with exact token
     *          metadata, tagged with the writer's org for tenant-scoped lookup.
     * @logic Stores the response text as the document content, and places the exact token count
     *        from the provider's Usage metadata in the document metadata map. String estimation is
     *        explicitly avoided — only real Usage counts are persisted. The {@code org_id} key
     *        partitions the cache per tenant (audit F17); writers without a bound org are skipped
     *        rather than leaked into a global pool.
     *
     * @param promptText   The (post-PII-redaction) user prompt — used as the vector embedding input.
     * @param content      The LLM response text to cache.
     * @param tokenCount   Exact prompt token count from Usage metadata. 0 means unknown.
     * @param modelId      Model that generated this response (for rate lookup on cache hit).
     * @param orgId        The caller's org. Null skips the write entirely.
     */
    private void cacheResponse(String promptText, String content, long tokenCount, String modelId, String orgId) {
        if (orgId == null) {
            // Without a bound org, this entry can never be safely retrieved (lookup requires
            // org_id match). Skip the write rather than create an orphan.
            return;
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("cachedResponse", content);
            metadata.put(META_ORG_ID, orgId);
            if (tokenCount > 0) {
                metadata.put(META_CACHED_TOKEN_COUNT, tokenCount);
            }
            if (modelId != null && !modelId.isBlank()) {
                metadata.put(META_CACHED_MODEL_ID, modelId);
            }
            Document doc = new Document(promptText, metadata);
            cacheVectorStore.add(List.of(doc));
        } catch (com.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException ex) {
            // Budget exhaustion during cache-write embedding must propagate (same
            // reason as the lookup-side: hard stop, not a soft cache-write failure).
            throw ex;
        } catch (Exception e) {
            log.warn("Failed to write to vector_cache (orgId={})", orgId, e);
        }
    }

    /**
     * @summary Publishes a Micrometer counter recording the estimated USD saved by this cache hit.
     * @logic Retrieves the original token count from Document metadata. If count > 0, translates
     *        to USD via LiveValuationEngine and increments the {@code finops.cache.savings.usd} counter.
     *        If no token count is available in metadata (pre-telemetry cached entry), logs and skips.
     *        String-based estimation is explicitly forbidden per the plan.
     *
     * @param doc The cached Document containing the original Usage metadata.
     */
    private void publishCacheSavings(Document doc) {
        try {
            Object rawCount = doc.getMetadata().get(META_CACHED_TOKEN_COUNT);
            if (rawCount == null) {
                log.debug("Cache hit found but no token count stored in metadata — savings not trackable for this entry.");
                return;
            }
            long tokenCount = ((Number) rawCount).longValue();
            if (tokenCount <= 0) return;

            Object rawModel = doc.getMetadata().get(META_CACHED_MODEL_ID);
            String modelId = rawModel instanceof String s ? s : "unknown";

            // Saved tokens are the prompt tokens that were avoided — output token count is 0 (no LLM call)
            double savedUsd = valuationEngine.calculateUsd(modelId, tokenCount, 0L);
            if (savedUsd <= 0) return;

            Counter.builder(METRIC_CACHE_SAVINGS_USD)
                .baseUnit("usd")
                .description("Estimated USD saved by semantic cache hits avoiding LLM inference")
                .tag("gen_ai.request.model", modelId)
                .register(meterRegistry)
                .increment(savedUsd);

            log.debug("FinOps Cache ROI: hit saved ~{}tok / ${:.4f} (model={})", tokenCount, savedUsd, modelId);
        } catch (Exception e) {
            log.warn("Failed to publish cache savings metric", e);
        }
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            String promptText = request.prompt().getContents();
            String orgId = AgentContextHolder.getOrgId();
            Document cachedDoc = getCachedDocumentOrNull(promptText, orgId);

            if (cachedDoc != null) {
                String cachedResponse = (String) cachedDoc.getMetadata().get("cachedResponse");
                if (cachedResponse != null && !cachedResponse.isBlank()) {
                    // F13 — re-moderate cached responses on every hit. The original moderation
                    // ran inside ContentSafetyAdvisor (order 20, downstream of this advisor),
                    // which means every cache hit short-circuits ContentSafety. A safety policy
                    // tightened after the cache write must still apply on replay; running
                    // checkContent synchronously here makes the cached response respect the
                    // current policy without reaching for a versioning scheme. Throws
                    // SecurityException on policy violation, which propagates to the caller
                    // exactly as a fresh ContentSafetyAdvisor block would.
                    moderationService.checkContent(cachedResponse);

                    log.info("AgentCacheHit: Semantic Match Found [similarity check passed, org={}]. Skipping LLM inference.", orgId);
                    publishCacheSavings(cachedDoc);
                    return new ChatClientResponse(
                        new org.springframework.ai.chat.model.ChatResponse(
                            List.of(new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage(cachedResponse)
                            ))
                        ),
                        java.util.Collections.emptyMap()
                    );
                }
            }

            ChatClientResponse response = chain.nextCall(request);

            if (promptText != null && !promptText.isBlank() && response != null && response.chatResponse() != null) {
                if (response.chatResponse().getResult() != null && response.chatResponse().getResult().getOutput() != null) {
                    String content = response.chatResponse().getResult().getOutput().getText();
                    if (content != null && !content.isBlank()) {
                        long tokenCount = 0L;
                        String modelId  = "unknown";
                        try {
                            var metadata = response.chatResponse().getMetadata();
                            if (metadata != null) {
                                if (metadata.getUsage() != null) {
                                    Integer pt = metadata.getUsage().getPromptTokens();
                                    if (pt != null) tokenCount = pt.longValue();
                                }
                                if (metadata.getModel() != null) {
                                    modelId = metadata.getModel();
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Could not extract token count for cache entry", e);
                        }
                        cacheResponse(promptText, content, tokenCount, modelId, orgId);
                    }
                }
            }
            return response;
        });
    }
}
