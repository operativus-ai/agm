package ai.operativus.agentmanager.compute.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import ai.operativus.agentmanager.control.service.KnowledgeService;
import ai.operativus.agentmanager.core.callback.AgentContextSnapshot;

/**
 * Domain Responsibility: Intercepts Spring AI ChatClient requests to inject augmented RAG contexts
 * using concurrent Semantic + Keyword search, fused into one ranked pool by Reciprocal Rank Fusion
 * (RRF), then refined to topN by a pluggable {@link DocumentReRanker} strategy (passthrough by
 * default = RRF's top N; a real reranker reorders by relevance).
 *
 * State: Stateless
 */
public class AdvancedRagAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AdvancedRagAdvisor.class);

    private final KnowledgeService knowledgeService;
    private final SearchRequest searchRequest;
    private final DocumentReRanker documentReRanker;
    /** Per-advisor processing-time timer — supports the §2 advisor-chain decomposition gap.
     *  Tag {@code advisor=advanced_rag} so a single timer name {@code advisor.duration_ms}
     *  carries all per-advisor latencies. RAG is typically the slowest advisor (vector store
     *  query + LLM-based reranking), so this is the most-pulling-attention timer. */
    private final io.micrometer.core.instrument.Timer durationTimer;

    public AdvancedRagAdvisor(KnowledgeService knowledgeService, SearchRequest searchRequest,
                              DocumentReRanker documentReRanker,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.knowledgeService = knowledgeService;
        this.searchRequest = searchRequest;
        this.documentReRanker = documentReRanker;
        this.durationTimer = io.micrometer.core.instrument.Timer.builder("advisor.duration_ms")
                .tag("advisor", "advanced_rag").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "AdvancedRagAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * @summary Intercepts synchronous chat client calls to augment the user prompt with external RAG context.
     * @logic Applies the RRF context gathering pipeline and returns the mutated ChatClientRequest.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            ChatClientRequest augmentedRequest = augmentRequest(request);
            return chain.nextCall(augmentedRequest);
        });
    }

    /**
     * @summary Intercepts streaming chat client calls to asynchronously augment the user prompt.
     * @logic Wraps the augmentation pipeline in a Mono and subscribes on a bounded elastic thread pool.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // F21 — augmentRequest depends on tenant-scoped ScopedValues (orgId for vector-store filter,
        // allowedKnowledgeBaseIds for per-agent allowlist). Reactor's boundedElastic does NOT
        // inherit JDK 21 ScopedValues from this thread; capture-and-rebind via AgentContextSnapshot
        // so the RAG pipeline (and the inner VTs it spawns inside augmentRequest) sees the right
        // tenant context. Without this, the vector store query runs unscoped and can return
        // documents from other tenants.
        AgentContextSnapshot snapshot = AgentContextSnapshot.capture();
        return reactor.core.publisher.Mono.fromCallable(() -> snapshot.call(() -> augmentRequest(request)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(augmentedRequest -> chain.nextStream(augmentedRequest));
    }

    /**
     * @summary Core RAG pipeline: concurrent hybrid retrieval + Reciprocal Rank Fusion.
     * @logic Spawns Virtual Threads to run semantic (vector) search and raw keyword search on the
     *     user query concurrently, fuses + dedups the two ranked lists via RRF into one ordered pool,
     *     then refines it to topN via the pluggable {@link DocumentReRanker} before injecting the top
     *     results into the system prompt. (Note: the query is embedded directly — there is no HyDE / hypothetical-
     *     document generation despite an earlier design note; the fast-model dependency was removed.)
     */
    private ChatClientRequest augmentRequest(ChatClientRequest request) {
        String userQuery = "";
        if (request.prompt() != null && request.prompt().getInstructions() != null) {
            userQuery = request.prompt().getInstructions().stream()
                    .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .findFirst()
                    .orElse("");
        }
        
        if (userQuery == null || userQuery.isBlank()) {
           return request; // Pass-through if no user query
        }

        log.info("Intercepted RAG Query. Executing concurrent search on: {}", userQuery);

        // Phase 1 & 2: Concurrent Semantic Search and Keyword Search
        int targetK = this.searchRequest.getTopK();
        int candidatePoolSize = Math.max(20, (targetK > 0 ? targetK : 5) * 4);
        
        final String fUserQuery = userQuery;
        List<Document> semanticDocs;
        List<Document> keywordDocs;

        // Inner VTs spawned below do NOT inherit ScopedValues either (a fresh VT only inherits
        // bindings via StructuredTaskScope, which this code path doesn't use). Capture once at
        // augmentRequest entry and rebind inside each submit lambda so KnowledgeService sees
        // the same orgId/allowedKnowledgeBaseIds as the caller. This is load-bearing for both
        // the streaming (boundedElastic) and sync (caller thread) paths — augmentRequest is
        // called from both adviseStream and adviseCall.
        AgentContextSnapshot innerSnapshot = AgentContextSnapshot.capture();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {

            // Execute semantic vector search on Virtual Thread.
            // Phase 2: pull the full candidatePoolSize from the semantic path. Previously this called
            // search(finalSearch.getQuery()), which DISCARDED the topK override — the semantic pool was
            // silently capped at KnowledgeService's default topK, starving the RRF + rerank stage.
            java.util.concurrent.Future<List<Document>> semanticFuture = executor.submit(() ->
                    innerSnapshot.call(() -> this.knowledgeService.search(fUserQuery, candidatePoolSize)));

            // Execute SQL DB keyword search concurrently
            java.util.concurrent.Future<List<Document>> keywordFuture = executor.submit(() ->
                    innerSnapshot.call(() -> this.knowledgeService.keywordSearch(fUserQuery, candidatePoolSize)));

            // Await both Java Virtual Threads natively with a strict timeout not to block indefinitely
            try {
                semanticDocs = semanticFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
                keywordDocs = keywordFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.error("CRITICAL THREAD HANG: RAG semantic/keyword scope timed out after 15 seconds.", e);
                semanticFuture.cancel(true);
                keywordFuture.cancel(true);
                semanticDocs = new java.util.ArrayList<>();
                keywordDocs = new java.util.ArrayList<>();
            } catch (Exception e) {
                log.error("Exception during concurrent RAG fetch.", e);
                semanticDocs = new java.util.ArrayList<>();
                keywordDocs = new java.util.ArrayList<>();
            }
        }

        // Phase 3: rank the candidates. RRF (Reciprocal Rank Fusion) is now the PRIMARY ranking —
        // it fuses the two ranked lists (semantic + keyword) into one de-duplicated, properly-ordered
        // pool. The DocumentReRanker then refines that pool down to topN: the passthrough takes RRF's
        // top N; a real reranker (when configured) reorders by relevance. Previously the merge was an
        // unordered HashMap and the passthrough returned an ARBITRARY subset — RRF ran only on exception.
        int topN = targetK > 0 ? targetK : 10;
        List<Document> fusedPool = applyReciprocalRankFusion(semanticDocs, keywordDocs, candidatePoolSize);
        log.info("RRF fused pool: {} unique documents from Semantic ({}) + Keyword ({}).",
                fusedPool.size(), semanticDocs.size(), keywordDocs.size());

        List<Document> rerankedDocs;
        try {
            rerankedDocs = documentReRanker.rerank(userQuery, fusedPool, topN);
            log.info("Re-ranking complete. Top {} documents selected.", rerankedDocs.size());
        } catch (Exception e) {
            log.warn("Re-ranking failed; using RRF order.", e);
            rerankedDocs = fusedPool.subList(0, Math.min(topN, fusedPool.size()));
        }

        // Format context
        String context = rerankedDocs.stream()
                .map(doc -> "--- Document from " + doc.getMetadata().getOrDefault("source_url", "Unknown") + " ---\n" + doc.getText())
                .collect(Collectors.joining("\n\n"));

        String ragPrompt = "\n\n---------------------\n" +
                           "KNOWLEDGE BASE CONTEXT:\n" +
                           context +
                           "\n---------------------\n" +
                           "IMPORTANT INSTRUCTION: If the provided knowledge base context is empty or not relevant, use your own general knowledge to answer the question to the best of your ability. Do not apologize for missing context.";

        // Re-build prompt instructions, unifying all SystemMessages to prevent GoogleGenAiChatModel IllegalArgumentExceptions
        List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        StringBuilder unifiedSystemText = new StringBuilder();
        
        if (request.prompt() != null && request.prompt().getInstructions() != null) {
            for (org.springframework.ai.chat.messages.Message msg : request.prompt().getInstructions()) {
                if (msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM) {
                    unifiedSystemText.append(msg.getText()).append("\n\n");
                } else {
                    messages.add(msg);
                }
            }
        }
        
        unifiedSystemText.append(ragPrompt);
        messages.add(0, new org.springframework.ai.chat.messages.SystemMessage(unifiedSystemText.toString()));

        org.springframework.ai.chat.prompt.Prompt augmentedPrompt = new org.springframework.ai.chat.prompt.Prompt(
             messages,
             request.prompt() != null ? request.prompt().getOptions() : null
        );

        return request.mutate()
                .prompt(augmentedPrompt)
                .build();
    }

    /**
     * @summary Primary ranking: Reciprocal Rank Fusion (RRF) of the semantic + keyword ranked lists.
     * @logic Combines the two ranked lists deterministically via the standard RRF formula
     *        (1.0 / (k + rank)), de-duplicating by document id and converging on the highest-fidelity
     *        context blocks. The fused pool is then refined to topN by the {@link DocumentReRanker}.
     */
    private List<Document> applyReciprocalRankFusion(List<Document> semanticDocs, List<Document> keywordDocs, int topK) {
        final int k = 60; // Standard constant for RRF
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docLookup = new HashMap<>();

        // Score Semantic Docs
        for (int i = 0; i < semanticDocs.size(); i++) {
            Document doc = semanticDocs.get(i);
            docLookup.putIfAbsent(doc.getId(), doc);
            double score = 1.0 / (k + i + 1);
            rrfScores.merge(doc.getId(), score, (a, b) -> a + b);
        }

        // Score Keyword Docs
        for (int i = 0; i < keywordDocs.size(); i++) {
            Document doc = keywordDocs.get(i);
            docLookup.putIfAbsent(doc.getId(), doc);
            double score = 1.0 / (k + i + 1);
            rrfScores.merge(doc.getId(), score, (a, b) -> a + b);
        }

        // Sort by RRF score descending and limit
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> docLookup.get(entry.getKey()))
                .collect(Collectors.toList());
    }
}
