package com.operativus.agentmanager.compute.memory;

import com.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import com.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity;
import com.operativus.agentmanager.core.model.MetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Domain Responsibility: Periodically polls the robust Event Sourced Outbox, safely extracts the LLM-centric vector 
 * generation away from frontend conversational pipelines, and delegates facts to the downstream Spring AI Vector Index.
 * State: Stateless
 */
@Service
public class MemoryConsolidationWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationWorker.class);

    private final AgenticMemoryOutboxRepository outboxRepository;
    private final com.operativus.agentmanager.control.repository.AgenticMemoryRepository agenticMemoryRepository;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Single-flight guard. Set to {@code true} for the duration of a consolidation pass to
     * prevent the manual admin trigger ({@link #consolidateNow()}) from queuing a second
     * concurrent pass while the cron-driven loop is mid-processing. The guard does NOT
     * serialize the cron loop with itself — Spring's {@code @Scheduled fixedDelay} already
     * guarantees the cron method does not overlap with itself; this guard exists solely to
     * coordinate manual triggers against the scheduled pass.
     */
    private final AtomicBoolean consolidationInFlight = new AtomicBoolean(false);

    public MemoryConsolidationWorker(AgenticMemoryOutboxRepository outboxRepository,
                                     com.operativus.agentmanager.control.repository.AgenticMemoryRepository agenticMemoryRepository,
                                     VectorStore vectorStore,
                                     ChatClient.Builder chatClientBuilder,
                                     JdbcTemplate jdbcTemplate) {
        this.outboxRepository = outboxRepository;
        this.agenticMemoryRepository = agenticMemoryRepository;
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @summary Triggered every 5 seconds. Thin wrapper around {@link #consolidateNow()}.
     * @logic Cron entry point — Spring's {@code fixedDelay} ensures the cron loop does not
     *        overlap with itself. The body is in {@code consolidateNow()} so the manual
     *        admin trigger ({@code POST /api/memories/consolidate}) can invoke the same
     *        code path under operator control.
     */
    @Scheduled(fixedDelayString = "${agentmanager.scheduler.memory-consolidation-ms:5000}")
    @Transactional
    public void processPendingMemoryExtractions() {
        consolidateNow();
    }

    /**
     * @summary Performs a single consolidation pass. Returns the number of outbox entries
     *          processed in this pass.
     * @logic Reentry-safe via {@link #consolidationInFlight} — concurrent callers (admin
     *        trigger landing while the cron loop is mid-processing) get back {@code -1} so
     *        they can report {@code alreadyRunning: true} to the operator. The cron loop
     *        always wins in case of contention because Spring's {@code @Scheduled} method
     *        is the steady-state path.
     * @return number of outbox entries processed; {@code -1} if another pass was already
     *         in flight and this call was skipped.
     */
    public int consolidateNow() {
        if (!consolidationInFlight.compareAndSet(false, true)) {
            log.debug("consolidateNow() invoked while a previous pass is still in flight — skipping");
            return -1;
        }
        try {
            // Poll limit chunks to prevent massive latency spikes if a backlog erupts
            List<AgenticMemoryOutboxEntity> pendingEvents = outboxRepository.findPendingEventsAndLock(10);

            if (pendingEvents.isEmpty()) {
                return 0;
            }

            log.info("Outbox Poller initiating background memory consolidation for {} items.", pendingEvents.size());
            int processed = 0;
            for (AgenticMemoryOutboxEntity event : pendingEvents) {
                processOne(event);
                processed++;
            }
            return processed;
        } finally {
            consolidationInFlight.set(false);
        }
    }

    private void processOne(AgenticMemoryOutboxEntity event) {
            try {
                // Securely lock the state
                event.setStatus(AgenticMemoryOutboxEntity.OutboxStatus.PROCESSING);
                event.setLockedAt(LocalDateTime.now());
                outboxRepository.saveAndFlush(event); // Explicitly flush the lock immediately if we weren't natively within Postgres Skip Locked blocks

                String rawMemory = event.getPayload();

                // Fetch the parent entity to securely extract boundary context
                java.util.Optional<com.operativus.agentmanager.core.entity.AgenticMemoryEntity> parentMemoryOpt = agenticMemoryRepository.findById(event.getMemoryId());

                // M5 production fix: resolve the parent memory's orgId from the original
                // vector_store row metadata (written by MemoryService.addMemory under the
                // M4 fix). The agentic_memories ledger has no org_id column, so vector_store
                // metadata is the single source of truth. A null orgId here means the parent
                // row was written without an agent/HTTP context (pre-M4 row or system-
                // background path) — we skip the cross-tenant similarity search AND the LLM
                // consolidation to avoid leaking content across tenants.
                String parentOrgId = resolveParentOrgId(parentMemoryOpt);

                // -------------------------------------------------------------
                // Zep-Style Entropy Prevention (Semantic Consolidation Phase)
                // -------------------------------------------------------------
                String semanticSynthesis = rawMemory;
                if (parentOrgId != null && !parentOrgId.isBlank()) {
                    // 1. Search for highly colliding memory contexts within the SAME org only.
                    //    Pre-M5 this was unfiltered — org A's outbox event could collide with
                    //    org B's memory and the LLM merge prompt would receive org B's text as
                    //    "existing context", leaking content across tenants.
                    FilterExpressionBuilder fb = new FilterExpressionBuilder();
                    Filter.Expression orgFilter = fb.eq(MetadataKeys.ORG_ID, parentOrgId).build();
                    List<Document> highlySimilarHistoricMemories = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(rawMemory)
                                    .filterExpression(orgFilter)
                                    .build());

                    // 2. If collisions exist, ask the LLM to cleanly combine the concepts before indexing
                    //    (avoids bloat of "User uses Python" & "User uses Rust now").
                    if (!highlySimilarHistoricMemories.isEmpty() && highlySimilarHistoricMemories.get(0).getScore() > 0.85) {
                        log.debug("Semantic collision detected within org {}. Triggering LLM consolidation.", parentOrgId);
                        String activeContext = highlySimilarHistoricMemories.get(0).getText();

                        semanticSynthesis = chatClient.prompt()
                                .system("You are an Agentic Memory Consolidator. Given an existing memory and a new memory fact, output a single clean, factual sentence combining their context. Prefer the new memory if they contradict.")
                                .user("Existing: " + activeContext + "\nNew: " + rawMemory)
                                .call()
                                .content();

                        log.info("Consolidated memory derived: {}", semanticSynthesis);
                    }
                } else {
                    log.debug("Parent memory {} has no resolvable orgId; skipping cross-tenant "
                            + "similarity search and LLM consolidation. Writing raw memory.",
                            event.getMemoryId());
                }

                // -------------------------------------------------------------
                // Secondary Index Sync Phase (Spring AI pgvector mapping)
                // -------------------------------------------------------------
                java.util.Map<String, Object> metadata = new java.util.HashMap<>();
                metadata.put("outboxId", event.getOutboxId().toString());
                metadata.put("memoryId", event.getMemoryId().toString());
                // MemoryService.searchMemories ANDs storeType=MEMORY into its filter (so KB
                // chunks don't leak into memory search). addMemory stamps it on every row;
                // the synthesized consolidation row must too, or it's written but invisible
                // to every post-consolidation memory search.
                metadata.put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_MEMORY);

                // M5 production fix: propagate orgId onto the synthesized Document so post-
                // consolidation rows remain visible to the M4 tenant-scoped search filter.
                // Pre-M5 the consolidated row had no orgId and was invisible to every search.
                if (parentOrgId != null && !parentOrgId.isBlank()) {
                    metadata.put(MetadataKeys.ORG_ID, parentOrgId);
                }

                if (parentMemoryOpt.isPresent()) {
                    com.operativus.agentmanager.core.entity.AgenticMemoryEntity parent = parentMemoryOpt.get();
                    metadata.put("userId", parent.getUserId() != null ? parent.getUserId() : "system");
                    metadata.put("memoryTier", parent.getMemoryTier() != null ? parent.getMemoryTier().name() : "USER_MEMORY");
                    if (parent.getAgentId() != null) {
                        metadata.put("agentId", parent.getAgentId());
                    }
                    if (parent.getTeamId() != null) {
                        metadata.put("teamId", parent.getTeamId());
                    }
                } else {
                    log.error("Fatal Constraint Error: Root AgenticMemoryEntity not found for memoryId: {}. Skipping vector metadata enrichment.", event.getMemoryId());
                }

                Document doc = new Document(semanticSynthesis, metadata);
                vectorStore.add(List.of(doc));

                // Mark Completed
                event.setStatus(AgenticMemoryOutboxEntity.OutboxStatus.COMPLETED);
                event.setUpdatedAt(LocalDateTime.now());
                outboxRepository.save(event);

            } catch (Exception e) {
                log.error("Outbox semantic insertion failed for Outbox ID: {}", event.getOutboxId(), e);
                event.setStatus(AgenticMemoryOutboxEntity.OutboxStatus.FAILED);
                event.setErrorMessage(e.getMessage());
                event.setRetryCount(event.getRetryCount() + 1);
                outboxRepository.save(event);
            }
    }

    /**
     * Reads the parent memory's orgId from the original {@code vector_store} row
     * metadata, located by {@link com.operativus.agentmanager.core.entity.AgenticMemoryEntity#getVectorId()}.
     * Returns null if the parent has no {@code vectorId}, the {@code vector_store} row
     * was deleted, or the row's metadata predates the M4 fix (no {@code orgId} key).
     */
    private String resolveParentOrgId(java.util.Optional<com.operativus.agentmanager.core.entity.AgenticMemoryEntity> parentMemoryOpt) {
        if (parentMemoryOpt.isEmpty()) return null;
        String vectorId = parentMemoryOpt.get().getVectorId();
        if (vectorId == null || vectorId.isBlank()) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT metadata->>'orgId' FROM vector_store WHERE id = ?::uuid",
                    String.class, vectorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        } catch (Exception ex) {
            log.warn("Failed to resolve parent orgId for vectorId={}: {}", vectorId, ex.getMessage());
            return null;
        }
    }
}
