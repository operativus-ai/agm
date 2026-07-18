package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Owns the {@code routing_vectors} write path. Two callers consume:
 *     {@link SemanticAgentScorer} (JIT-on-query) and the admin backfill endpoint (eager).
 *     Centralizing here keeps Document shape + embed-text composition + dedup probe in one
 *     place so the two paths can't drift.
 * State: Stateless (Spring singleton)
 */
@Service
public class AgentEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(AgentEmbeddingService.class);

    private final VectorStore vectorStore;
    private final AgentRepository agentRepository;
    private final AgentRegistry agentRegistry;

    public AgentEmbeddingService(@Qualifier("routingVectorStore") VectorStore vectorStore,
                                  AgentRepository agentRepository,
                                  AgentRegistry agentRegistry) {
        this.vectorStore = vectorStore;
        this.agentRepository = agentRepository;
        this.agentRegistry = agentRegistry;
    }

    /**
     * Embeds any agent in {@code candidates} whose description isn't already present in the
     * routing vector store for the given org. Returns the number of newly-added documents.
     * Used by {@link SemanticAgentScorer} on every query for self-healing JIT behavior.
     */
    public int embedMissing(String orgId, List<AgentDefinition> candidates) {
        if (orgId == null || orgId.isBlank() || candidates == null || candidates.isEmpty()) {
            return 0;
        }
        Set<String> candidateIds = candidates.stream().map(AgentDefinition::id).collect(Collectors.toSet());
        Set<String> alreadyEmbedded = probeExisting(orgId, candidateIds);
        List<Document> toAdd = candidates.stream()
                .filter(a -> a.id() != null && !alreadyEmbedded.contains(a.id()))
                .filter(a -> a.description() != null && !a.description().isBlank())
                .map(a -> buildDocument(orgId, a))
                .toList();
        if (!toAdd.isEmpty()) {
            vectorStore.add(toAdd);
            log.debug("AgentEmbeddingService embedded {} new agent descriptions for org {}", toAdd.size(), orgId);
        }
        return toAdd.size();
    }

    /**
     * Eager backfill: re-embeds every active agent in the org (overwriting existing rows).
     * Returns a summary so the admin endpoint can show what happened.
     */
    public BackfillSummary embedAll(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return new BackfillSummary(0, 0);
        }
        List<AgentDefinition> agents = agentRegistry.findAll(false, orgId);
        if (agents.isEmpty()) {
            return new BackfillSummary(0, 0);
        }
        List<Document> docs = agents.stream()
                .filter(a -> a.id() != null && a.description() != null && !a.description().isBlank())
                .map(a -> buildDocument(orgId, a))
                .toList();
        if (!docs.isEmpty()) {
            vectorStore.add(docs);
            log.info("AgentEmbeddingService eager-backfilled {} agent embeddings for org {}", docs.size(), orgId);
        }
        return new BackfillSummary(agents.size(), docs.size());
    }

    /**
     * Probes the routing vector store for which agents in {@code candidateIds} are already
     * embedded. Approximate (relies on filter-by-orgId + topK pull); good enough for JIT
     * dedup since re-embedding via {@code add(...)} just overwrites on document id collision.
     */
    private Set<String> probeExisting(String orgId, Set<String> candidateIds) {
        if (candidateIds.isEmpty()) return Set.of();
        SearchRequest probe = SearchRequest.builder()
                .query("agent description")
                .topK(Math.max(candidateIds.size() * 2, 16))
                .similarityThresholdAll()
                .filterExpression("orgId == '" + sanitize(orgId) + "'")
                .build();
        List<Document> existing = vectorStore.similaritySearch(probe);
        Set<String> ids = new HashSet<>();
        if (existing == null) return ids;
        for (Document d : existing) {
            Object aid = d.getMetadata().get("agentId");
            if (aid instanceof String s && candidateIds.contains(s)) {
                ids.add(s);
            }
        }
        return ids;
    }

    private Document buildDocument(String orgId, AgentDefinition agent) {
        return Document.builder()
                .id(documentId(orgId, agent.id()))
                .text(buildEmbedText(orgId, agent))
                .metadata("orgId", orgId)
                .metadata("agentId", agent.id())
                .build();
    }

    /**
     * Concatenates description + capabilities (DR-FR-7) so the embedding captures both
     * narrative and tagged skill labels.
     */
    private String buildEmbedText(String orgId, AgentDefinition agent) {
        StringBuilder sb = new StringBuilder(agent.description());
        try {
            agentRepository.findByIdAndOrgId(agent.id(), orgId)
                    .map(AgentEntity::getCapabilities)
                    .filter(arr -> arr != null && arr.length > 0)
                    .ifPresent(arr -> {
                        sb.append(" Capabilities: ");
                        sb.append(String.join(", ", arr));
                    });
        } catch (Exception ignore) {
            // capabilities are advisory — embed description alone on lookup failure
        }
        return sb.toString();
    }

    private static String documentId(String orgId, String agentId) {
        // routing_vectors.id is a uuid column (PgVectorStore default schema), so the document
        // id MUST be a valid UUID — PgVectorStore calls UUID.fromString(doc.id()) on insert.
        // Derive a deterministic name-based UUID from the stable (org, agent) key so re-embedding
        // overwrites the same row (the idempotent-add behaviour embedAll/embedMissing rely on).
        return UUID.nameUUIDFromBytes(("routing:" + orgId + ":" + agentId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sanitize(String s) {
        return s.replace("'", "''");
    }

    public record BackfillSummary(int totalAgents, int embedded) {}
}
