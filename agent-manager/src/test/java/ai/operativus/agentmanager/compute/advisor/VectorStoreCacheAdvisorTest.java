package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link VectorStoreCacheAdvisor} producer-side telemetry: the
 * {@code finops.cache.savings.usd} counter is emitted on cache hits and NOT
 * emitted on cache misses or any branch where the saved USD is unknown or zero.
 *
 * <p>Counterpart to PR #945 (Bug #45), which fixed the consumer-side query
 * (DistributionSummary vs. Counter meter-type mismatch) in
 * {@code FinOpsAnalyticsService}. That fix has a test; the emitter side has
 * been uncovered until now.
 *
 * <p>Cases:
 * <ol>
 *   <li>Cache hit with valid metadata — counter incremented, chain skipped.</li>
 *   <li>Cache miss — chain called, counter not incremented.</li>
 *   <li>Cache hit with no token count in metadata — counter not incremented.</li>
 *   <li>Cache hit but valuation returns 0.0 — counter not incremented.</li>
 *   <li>Cache hit but moderation throws — exception propagates, counter not incremented.</li>
 *   <li>Cache hit emits counter tagged with the cached model id.</li>
 *   <li>OrgId unbound — cache lookup skipped, counter not incremented.</li>
 *   <li>Order pinned at 15 (after PII boundary at 10, before content safety at 20).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VectorStoreCacheAdvisorTest {

    private static final String METRIC = "finops.cache.savings.usd";
    private static final String MODEL_TAG = "gen_ai.request.model";

    @Mock private VectorStore cacheVectorStore;
    @Mock private LiveValuationEngine valuationEngine;
    @Mock private ModerationService moderationService;
    @Mock private CallAdvisorChain chain;

    private SimpleMeterRegistry meterRegistry;
    private VectorStoreCacheAdvisor advisor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        advisor = new VectorStoreCacheAdvisor(cacheVectorStore, meterRegistry, valuationEngine, moderationService);
    }

    @AfterEach
    void clearAuthContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cacheHit_withValidMetadata_emitsSavingsCounter_andSkipsChain() {
        Document cached = cachedDoc("from cache", 4500L, "gpt-4o");
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(cached));
        when(valuationEngine.calculateUsd("gpt-4o", 4500L, 0L)).thenReturn(0.0125d);

        ChatClientResponse out = withOrg("acme", () -> advisor.adviseCall(request("hello"), chain));

        assertThat(out).isNotNull();
        Counter c = meterRegistry.find(METRIC).tag(MODEL_TAG, "gpt-4o").counter();
        assertThat(c).as("counter must exist after hit").isNotNull();
        assertThat(c.count()).isEqualTo(0.0125d);
        verifyNoInteractions(chain);
    }

    @Test
    void cacheMiss_invokesChain_andDoesNotEmitCounter() {
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ChatClientResponse downstream = ChatClientResponse.builder().build();
        when(chain.nextCall(any())).thenReturn(downstream);

        ChatClientResponse out = withOrg("acme", () -> advisor.adviseCall(request("hello"), chain));

        assertThat(out).isSameAs(downstream);
        verify(chain).nextCall(any());
        assertThat(meterRegistry.find(METRIC).counter()).isNull();
        verifyNoInteractions(moderationService);
    }

    @Test
    void cacheHit_butNoTokenCountInMetadata_doesNotEmitCounter() {
        // Pre-telemetry cached entry — no cachedTokenCount key. Hit still served
        // (moderation runs, chain skipped) but counter cannot be emitted.
        Map<String, Object> meta = new HashMap<>();
        meta.put("cachedResponse", "from cache");
        meta.put("org_id", "acme");
        Document cached = new Document("prompt", meta);
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(cached));

        ChatClientResponse out = withOrg("acme", () -> advisor.adviseCall(request("hello"), chain));

        assertThat(out).isNotNull();
        assertThat(meterRegistry.find(METRIC).counter()).isNull();
        verifyNoInteractions(chain);
        verifyNoInteractions(valuationEngine);
    }

    @Test
    void cacheHit_butValuationReturnsZero_doesNotEmitCounter() {
        Document cached = cachedDoc("from cache", 100L, "unknown-model");
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(cached));
        when(valuationEngine.calculateUsd("unknown-model", 100L, 0L)).thenReturn(0.0d);

        withOrg("acme", () -> advisor.adviseCall(request("hello"), chain));

        assertThat(meterRegistry.find(METRIC).counter()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void cacheHit_moderationThrows_propagates_andDoesNotEmitCounter() {
        Document cached = cachedDoc("policy-violating", 100L, "gpt-4o");
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(cached));
        when(moderationService.checkContent("policy-violating"))
                .thenThrow(new SecurityException("blocked by policy"));

        assertThatThrownBy(() -> withOrg("acme", () -> advisor.adviseCall(request("hello"), chain)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("blocked");

        assertThat(meterRegistry.find(METRIC).counter())
                .as("moderation runs before publishCacheSavings; counter must not have been incremented")
                .isNull();
        verifyNoInteractions(chain);
        verifyNoInteractions(valuationEngine);
    }

    @Test
    void cacheHit_emitsCounterTaggedWithCachedModelId() {
        // Replay-time rate lookup must use the model that ORIGINALLY produced
        // the cached response (stored in metadata), not the request's intended
        // model — otherwise saved-USD attribution would be misreported on hits
        // where the agent's default model has since changed.
        Document cached = cachedDoc("from cache", 1000L, "claude-sonnet-4");
        when(cacheVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(cached));
        when(valuationEngine.calculateUsd("claude-sonnet-4", 1000L, 0L)).thenReturn(0.003d);

        withOrg("acme", () -> advisor.adviseCall(request("hello"), chain));

        Counter c = meterRegistry.find(METRIC).tag(MODEL_TAG, "claude-sonnet-4").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(0.003d);
        // Negative assertion: no counter for the wrong model tag.
        assertThat(meterRegistry.find(METRIC).tag(MODEL_TAG, "gpt-4o").counter()).isNull();
    }

    @Test
    void orgIdUnbound_skipsCacheLookup_noCounterEmit() {
        // Lookup is intentionally tenant-scoped (audit F17). No bound org →
        // null cache result without a vector_store call, chain runs normally,
        // counter stays at zero. Write path is also skipped because orgId=null.
        ChatClientResponse downstream = ChatClientResponse.builder().build();
        when(chain.nextCall(any())).thenReturn(downstream);

        ChatClientResponse out = advisor.adviseCall(request("hello"), chain);

        assertThat(out).isSameAs(downstream);
        verifyNoInteractions(cacheVectorStore);
        assertThat(meterRegistry.find(METRIC).counter()).isNull();
    }

    @Test
    void orderingAndName_arePinned() {
        // Order 15 sits AFTER PIIAnonymizationAdvisor (order 10) per audit F11.
        // A silent reorder below 10 would leak raw PII into the embedding API
        // and the cache. AdvisorPiiBoundaryContractTest enforces the boundary;
        // this is the simple unit pin.
        assertThat(advisor.getOrder()).isEqualTo(15);
        assertThat(advisor.getName()).isEqualTo("VectorStoreCacheAdvisor");
    }

    private static Document cachedDoc(String response, long tokens, String modelId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("cachedResponse", response);
        meta.put("cachedTokenCount", tokens);
        meta.put("cachedModelId", modelId);
        meta.put("org_id", "acme");
        return new Document("prompt", meta);
    }

    private static ChatClientRequest request(String text) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(text)))
                .context(new HashMap<>())
                .build();
    }

    private static <T> T withOrg(String orgId, java.util.concurrent.Callable<T> body) {
        try {
            return ScopedValue.where(AgentContextHolder.orgId, orgId).call(body::call);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
