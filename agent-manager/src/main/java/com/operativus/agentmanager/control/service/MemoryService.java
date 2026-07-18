package com.operativus.agentmanager.control.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import com.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import com.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity;
import com.operativus.agentmanager.core.model.MetadataKeys;
import com.operativus.agentmanager.core.model.MetricConstants;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Accepts memory propositions rapidly and commits them safely to the authoritative JPA ledger and Outbox queue, protecting DB connections from LLM latency.
 * State: Stateless
 */
@Service
public class MemoryService implements com.operativus.agentmanager.core.registry.MemoryOperations {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private final VectorStore vectorStore;
    private final org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder;
    private final com.operativus.agentmanager.compute.memory.PgVectorGraphRepository graphRepository;
    private final AgenticMemoryRepository agenticMemoryRepository;
    private final AgenticMemoryOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;
    private final Counter searchMemoriesCounter;
    private final Counter searchUserMemoriesCounter;

    public MemoryService(VectorStore vectorStore,
                         org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder,
                         com.operativus.agentmanager.compute.memory.PgVectorGraphRepository graphRepository,
                         AgenticMemoryRepository agenticMemoryRepository,
                         AgenticMemoryOutboxRepository outboxRepository,
                         PlatformTransactionManager transactionManager,
                         MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.chatClientBuilder = chatClientBuilder;
        this.graphRepository = graphRepository;
        this.agenticMemoryRepository = agenticMemoryRepository;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.searchMemoriesCounter = Counter.builder(MetricConstants.MEMORY_SEARCH_TOTAL)
                .tag("source", "memories")
                .register(meterRegistry);
        this.searchUserMemoriesCounter = Counter.builder(MetricConstants.MEMORY_SEARCH_TOTAL)
                .tag("source", "user_memories")
                .register(meterRegistry);
    }

    @Transactional
    public void addMemory(String content) {
        // Resolve the caller's userId from the active context rather than hardcoding
        // SYSTEM_RUNTIME. Pre-fix every memory written through MemoryController landed
        // under userId="SYSTEM_RUNTIME" regardless of the authenticated principal, which
        // collapsed the per-user vector filter at search time — every user effectively
        // shared the SYSTEM_RUNTIME pool. Falls back to "anonymous" only when neither
        // AgentContextHolder nor SecurityContextHolder bind a user.
        addMemory(content, resolveCurrentUserId());
    }

    @Transactional
    public void addMemory(String content, String userId) {
        addMemory(content, userId, null);
    }

    @Transactional
    public void addMemory(String content, String userId, List<String> topics) {
        log.info("Recording new memory for user: {}", userId);

        String vectorDocId = UUID.randomUUID().toString();

        // 1. Vectorize synchronously so search is immediately available.
        // M4 production fix: write orgId into Document metadata so tenant-scoped retrieval
        // can filter on it. Previously the metadata held only userId — every search across
        // the vector_store returned cross-tenant rows.
        // storeType=MEMORY discriminates memory rows from KnowledgeService chunks in the
        // shared vector_store. Search filters on both sides AND this predicate so memory
        // search no longer returns KB documents.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", userId != null ? userId : "anonymous");
        metadata.put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_MEMORY);
        String orgId = resolveCurrentOrgId();
        if (orgId != null && !orgId.isBlank()) {
            metadata.put(MetadataKeys.ORG_ID, orgId);
        }
        Document doc = new Document(vectorDocId, content, metadata);
        vectorStore.add(List.of(doc));

        // 2. Relational persistence (Authoritative Ledger)
        // M7 production fix: mirror orgId written to vector metadata (M4) into the
        // relational row so org-scoped deletion no longer requires a vector join.
        AgenticMemoryEntity memoryEntity = new AgenticMemoryEntity();
        memoryEntity.setMemoryId(UUID.randomUUID());
        memoryEntity.setMemory(content);
        memoryEntity.setUserId(userId != null ? userId : "anonymous");
        if (orgId != null && !orgId.isBlank()) {
            memoryEntity.setOrgId(orgId);
        }
        memoryEntity.setMemoryTier(AgenticMemoryEntity.MemoryTier.USER_MEMORY);
        memoryEntity.setVectorId(vectorDocId);
        memoryEntity.setTopics(topics);
        memoryEntity.setCreatedAt(LocalDateTime.now());
        agenticMemoryRepository.save(memoryEntity);

        // 3. Queue outbox entry for any future background processing
        AgenticMemoryOutboxEntity outbox = new AgenticMemoryOutboxEntity();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setMemoryId(memoryEntity.getMemoryId());
        outbox.setPayload(content);
        outbox.setStatus(AgenticMemoryOutboxEntity.OutboxStatus.PENDING);
        outbox.setCreatedAt(LocalDateTime.now());
        outboxRepository.save(outbox);
    }

    public List<String> searchMemories(String query) {
        return searchMemories(query, null);
    }

    /**
     * @summary Tenant- and (optionally) user-scoped semantic memory search.
     * @param query           the natural-language query to match
     * @param permittedUserId when non-null, only matches with metadata.userId equal to
     *                        this value are returned. Pass {@code null} for admin /
     *                        cross-user views (org scope still applies).
     *
     * <p>Pre-fix this method only filtered by orgId — any user in the org could
     * semantic-search any other user's memories within the same tenant. The userId
     * metadata key has been on every persisted document since {@code addMemory}
     * (it was just never queried). Filter syntax mirrors {@code searchUserMemories}
     * (line ~169) which already used {@code b.eq("userId", userId)}.
     */
    public List<String> searchMemories(String query, String permittedUserId) {
        log.debug("Performing vector search for memories with query: [{}] permittedUserId={}",
                query, permittedUserId);
        String orgId = resolveCurrentOrgId();
        if (orgId == null || orgId.isBlank()) {
            // M4 production fix: refuse to query without an orgId. Pre-fix the call
            // returned every memory across every tenant — a global cross-tenant leak.
            log.warn("Refusing memory search: no orgId resolvable from AgentContextHolder "
                    + "or SecurityContext. Returning empty result.");
            return List.of();
        }
        searchMemoriesCounter.increment();
        // AND storeType=MEMORY so KB chunks in the shared vector_store don't leak into
        // memory search results within the same tenant. Pre-fix the filter was orgId
        // (and optional userId) only — same-org KB documents surfaced as memory hits.
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = permittedUserId != null
                ? b.and(
                    b.eq(MetadataKeys.ORG_ID, orgId),
                    b.and(
                        b.eq(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_MEMORY),
                        b.eq("userId", permittedUserId))).build()
                : b.and(
                    b.eq(MetadataKeys.ORG_ID, orgId),
                    b.eq(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_MEMORY)).build();
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .filterExpression(filter)
                        .topK(10)
                        .build()
        ).stream()
         .map(Document::getText)
         .collect(Collectors.toList());
    }

    public List<String> searchUserMemories(String userId) {
        if (userId == null || userId.isBlank()) return List.of();
        log.debug("Fetching semantic rules for user: {}", userId);
        String orgId = resolveCurrentOrgId();
        if (orgId == null || orgId.isBlank()) {
            // M4 production fix: refuse to query without an orgId. The pre-fix
            // userId-only filter let an attacker in org A craft a known userId from
            // org B and pull that user's memories cross-tenant.
            log.warn("Refusing user-memory search for userId={}: no orgId resolvable from "
                    + "AgentContextHolder or SecurityContext. Returning empty result.", userId);
            return List.of();
        }
        searchUserMemoriesCounter.increment();
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = b.and(
                b.and(b.eq("userId", userId), b.eq(MetadataKeys.ORG_ID, orgId)),
                b.eq(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_MEMORY)
        ).build();
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("User preferences and semantic rules")
                        .filterExpression(filter)
                        .topK(10)
                        .build()
        ).stream()
         .map(Document::getText)
         .collect(Collectors.toList());
    }

    /**
     * Resolves the active orgId for memory writes and tenant-bounded searches. Prefers the
     * agent-run {@code AgentContextHolder} ScopedValue (bound by {@code AgentService.run}
     * around the advisor chain). Falls back to the HTTP SecurityContext principal so
     * {@code MemoryController}'s GET /api/memories path is also tenant-scoped. Returns
     * null only when neither context binds an orgId — callers must treat that as a hard
     * refusal (return empty), never as "match everything".
     */
    private String resolveCurrentOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId != null && !orgId.isBlank()) return orgId;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getOrgId();
        }
        return null;
    }

    /**
     * Resolves the active userId for memory writes. Same context-precedence as
     * {@link #resolveCurrentOrgId} — agent-run ScopedValue first, then HTTP principal.
     * Falls back to {@code "anonymous"} (never {@code "SYSTEM_RUNTIME"}) when no caller
     * identity is bound, so the field is at least non-blank for the per-user filter.
     */
    private String resolveCurrentUserId() {
        String userId = AgentContextHolder.getUserId();
        if (userId != null && !userId.isBlank()) return userId;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getId() != null ? ud.getId().toString() : ud.getUsername();
        }
        return "anonymous";
    }

    @Transactional
    public void deleteMemories(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        String callerOrgId = resolveCurrentOrgId();
        if (callerOrgId == null || callerOrgId.isBlank()) {
            // Same posture as searchMemories: refuse without a tenant. Pre-fix this method
            // skipped the tenant check entirely and any admin could delete any tenant's
            // memories by vector_id (cross-tenant exploit, same shape as PR #972 cancelRun).
            log.warn("Refusing memory delete: no orgId resolvable from AgentContextHolder "
                    + "or SecurityContext. {} ids ignored.", ids.size());
            return;
        }
        log.info("Deleting memory document(s) for org '{}': requested={}", callerOrgId, ids.size());

        // M7 production fix: was findAll().stream().filter() — full table scan.
        // findByVectorIdIn issues a WHERE vector_id IN (...) query.
        List<AgenticMemoryEntity> matching = agenticMemoryRepository.findByVectorIdIn(ids);
        // Cross-tenant guard: drop rows that belong to a different orgId. The caller may
        // be a foreign-tenant admin probing with another org's vector ids; we silently
        // no-op those (no information leak about whether the id exists elsewhere).
        List<AgenticMemoryEntity> ownRows = matching.stream()
                .filter(e -> callerOrgId.equals(e.getOrgId()))
                .toList();
        if (ownRows.isEmpty()) {
            log.info("No own-tenant rows match requested ids for org '{}' — no-op", callerOrgId);
            return;
        }
        List<String> ownVectorIds = ownRows.stream()
                .map(AgenticMemoryEntity::getVectorId)
                .filter(v -> v != null && !v.isBlank())
                .toList();
        if (!ownVectorIds.isEmpty()) {
            vectorStore.delete(ownVectorIds);
        }
        List<java.util.UUID> memoryIds = ownRows.stream()
                .map(AgenticMemoryEntity::getMemoryId)
                .toList();
        outboxRepository.deleteByMemoryIdIn(memoryIds);
        agenticMemoryRepository.deleteAll(ownRows);
    }

    @Transactional
    public void deleteAllMemoriesForOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) return;
        log.info("Deleting all memories for org: {}", orgId);

        List<AgenticMemoryEntity> orgMemories = agenticMemoryRepository.findByOrgId(orgId);
        if (orgMemories.isEmpty()) {
            log.info("No memories found for org {} — skipping.", orgId);
            return;
        }

        List<String> vectorIds = orgMemories.stream()
                .map(AgenticMemoryEntity::getVectorId)
                .filter(v -> v != null && !v.isBlank())
                .toList();
        if (!vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds);
        }

        List<java.util.UUID> memoryIds = orgMemories.stream()
                .map(AgenticMemoryEntity::getMemoryId)
                .toList();
        outboxRepository.deleteByMemoryIdIn(memoryIds);
        agenticMemoryRepository.deleteAll(orgMemories);

        log.info("Deleted {} memories for org: {}", orgMemories.size(), orgId);
    }

    public void optimizeMemories(String userId) {
        log.info("Optimizing memories for user: {}", userId);

        List<AgenticMemoryEntity> memories = agenticMemoryRepository.findByUserId(userId);
        if (memories.isEmpty()) {
            log.info("No memories found for user {} — skipping optimization.", userId);
            return;
        }

        if (chatClientBuilder == null) {
            log.warn("ChatClientBuilder is null, cannot perform LLM-based memory optimization.");
            return;
        }

        int batchSize = 20;
        List<List<AgenticMemoryEntity>> batches = new ArrayList<>();
        for (int i = 0; i < memories.size(); i += batchSize) {
            batches.add(memories.subList(i, Math.min(i + batchSize, memories.size())));
        }

        int consolidatedCount = 0;
        for (List<AgenticMemoryEntity> batch : batches) {
            if (batch.size() < 3) continue;

            String facts = batch.stream()
                    .map(AgenticMemoryEntity::getMemory)
                    .filter(c -> c != null && !c.isBlank())
                    .collect(Collectors.joining("\n- ", "- ", ""));

            try {
                var client = chatClientBuilder.build();

                String summary = client.prompt()
                        .system("You are a memory consolidation agent. Summarize the following facts into a concise, deduplicated set of key memories. Remove redundancies and merge related facts. Return only the consolidated facts as a bullet list.")
                        .user(facts)
                        .call()
                        .content();

                if (summary == null || summary.isBlank()) continue;

                // Extract topics from the consolidated summary
                String topicsRaw = client.prompt()
                        .system("Extract 3-7 key topic tags from this memory summary. Return only comma-separated lowercase words or short phrases. No punctuation, no numbering, no explanation.")
                        .user(summary)
                        .call()
                        .content();

                final List<String> topics = (topicsRaw == null || topicsRaw.isBlank())
                        ? null
                        : Arrays.stream(topicsRaw.split(","))
                                .map(String::trim)
                                .map(String::toLowerCase)
                                .filter(t -> !t.isBlank())
                                .collect(Collectors.toList());

                // M6 production fix: add-first, delete-after, wrapped in a single
                // transaction. Pre-fix bugs:
                //   (1) Delete-then-add order caused PERMANENT data loss when embed failed
                //       on the consolidated replacement — old ledger + vector rows were
                //       already committed-deleted by the time addMemory threw, and the
                //       catch below silently swallowed the exception.
                //   (2) outboxRepository.deleteByMemoryIdIn requires an active transaction
                //       (it's @Modifying JPQL DELETE) — pre-fix the bare call threw
                //       "No active transaction for update or delete query" which the catch
                //       also swallowed. Compounded with the fk_agentic_memory_outbox_memory
                //       FK from agentic_memory_outbox.memory_id to agentic_memories, this
                //       meant agenticMemoryRepository.deleteAll always threw on any user
                //       with PENDING outbox rows — optimization was a silent no-op in
                //       production.
                //
                // We do NOT span the LLM calls with this transaction — that would hold a DB
                // connection through multi-second network I/O. The transaction wraps only
                // the four DB writes (add + outbox-delete + vector-delete + ledger-delete)
                // so they commit or roll back together.
                transactionTemplate.executeWithoutResult(status -> {
                    addMemory(summary, userId, topics);

                    List<java.util.UUID> oldMemoryIds = batch.stream()
                            .map(AgenticMemoryEntity::getMemoryId)
                            .toList();
                    outboxRepository.deleteByMemoryIdIn(oldMemoryIds);

                    List<String> vectorIdsToDelete = batch.stream()
                            .map(AgenticMemoryEntity::getVectorId)
                            .filter(v -> v != null && !v.isBlank())
                            .toList();
                    if (!vectorIdsToDelete.isEmpty()) {
                        vectorStore.delete(vectorIdsToDelete);
                    }
                    agenticMemoryRepository.deleteAll(batch);
                });

                consolidatedCount += batch.size();

            } catch (Exception e) {
                log.warn("Memory optimization batch failed for user {}: {}", userId, e.getMessage());
            }
        }

        log.info("Memory optimization complete for user {}. Consolidated {} memories.", userId, consolidatedCount);
    }

    public Map<String, Object> getMemoryStats(String userId) {
        log.debug("Fetching memory stats for user: {}", userId);
        List<AgenticMemoryEntity> userMemories = agenticMemoryRepository.findByUserId(userId);
        List<String> topics = extractTopicsFromMemories(userMemories);

        return Map.of(
            "total_memories", (long) userMemories.size(),
            "topics", topics,
            "last_updated", java.time.Instant.now().toString()
        );
    }

    public List<String> getMemoryTopics(String userId) {
        log.debug("Fetching memory topics for user: {}", userId);
        List<AgenticMemoryEntity> userMemories = agenticMemoryRepository.findByUserId(userId);
        return extractTopicsFromMemories(userMemories);
    }

    private List<String> extractTopicsFromMemories(List<AgenticMemoryEntity> memories) {
        return memories.stream()
                .filter(m -> m.getTopics() != null && !m.getTopics().isEmpty())
                .flatMap(m -> m.getTopics().stream())
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
