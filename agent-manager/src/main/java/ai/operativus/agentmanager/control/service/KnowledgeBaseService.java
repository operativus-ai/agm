package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.controller.model.AgentSummary;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import ai.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.KnowledgeContent;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: Lifecycle management for KnowledgeBase containers, including cascade deletion and agent-assignment queries.
 * State: Stateless
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeContentRepository knowledgeContentRepository;
    private final KnowledgeService knowledgeService;
    private final AgentRepository agentRepository;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBaseRepository,
                                KnowledgeContentRepository knowledgeContentRepository,
                                KnowledgeService knowledgeService,
                                AgentRepository agentRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeContentRepository = knowledgeContentRepository;
        this.knowledgeService = knowledgeService;
        this.agentRepository = agentRepository;
    }

    /**
     * Deletes the KB record immediately and spawns a virtual thread for async cascade cleanup.
     * Each content batch and agent cleanup runs in its own independent transaction so no single
     * long-lived DB connection is held. The controller returns 202 ACCEPTED immediately.
     */
    public void deleteWithCascade(UUID kbId) {
        log.info("Initiating cascade delete for knowledge base ID: {}", kbId);
        if (!knowledgeBaseRepository.existsById(kbId)) {
            throw new IllegalArgumentException("Knowledge base not found: " + kbId);
        }
        // Delete KB record immediately — repository method manages its own transaction
        knowledgeBaseRepository.deleteById(kbId);
        // F11 — fresh VT does NOT inherit JDK 21 ScopedValues. Capture caller's AgentContextHolder
        // bindings (orgId, userId, etc.) so the cascade body and downstream knowledgeService.delete()
        // calls see the right tenant context — important for the audit trail / tenant-scoped vector
        // store deletes inside knowledgeService.delete().
        final ai.operativus.agentmanager.core.callback.AgentContextSnapshot snapshot =
                ai.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        // Content and agent cleanup runs asynchronously; each knowledgeService.delete() is @Transactional
        Thread.ofVirtual().start(() -> snapshot.run(() -> cascadeDeleteContent(kbId)));
    }

    private void cascadeDeleteContent(UUID kbId) {
        log.info("Starting async cascade content cleanup for KB ID: {}", kbId);
        try {
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 200);
            org.springframework.data.domain.Page<KnowledgeContent> page;
            do {
                page = knowledgeContentRepository.findByKnowledgeBaseId(kbId, pageable);
                for (KnowledgeContent content : page.getContent()) {
                    try {
                        knowledgeService.delete(content.getId());
                    } catch (Exception e) {
                        log.error("Failed to delete content ID {} during cascade for KB {}: {}", content.getId(), kbId, e.getMessage());
                    }
                }
                pageable = pageable.next();
            } while (page.hasNext());
            cleanupAgentReferences(kbId);
            log.info("Async cascade delete complete for KB ID: {}", kbId);
        } catch (Exception e) {
            log.error("Async cascade delete failed for KB ID {}: {}", kbId, e.getMessage(), e);
        }
    }

    private void cleanupAgentReferences(UUID kbId) {
        String kbIdStr = kbId.toString();
        List<AgentEntity> agents = agentRepository.findByKnowledgeBaseIdsContaining(kbIdStr);
        int removed = 0;
        for (AgentEntity agent : agents) {
            if (removeKbReferenceWithRetry(agent.getId(), kbIdStr, kbId)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed KB {} reference from {} agents", kbId, removed);
        }
    }

    /**
     * Removes {@code kbIdStr} from a single agent's {@code knowledgeBaseIds} with a 3-attempt
     * optimistic-lock retry. Mirrors the pattern in {@link
     * ai.operativus.agentmanager.compute.service.AgentRunFinalizer}: each retry reloads the
     * fresh row so we never clobber a newer, higher-version state. On exhausted retries the
     * stale KB reference remains until the next agent edit overwrites the list — a benign,
     * self-healing inconsistency. Tier 1.9 audit finding F1.
     */
    private boolean removeKbReferenceWithRetry(String agentId, String kbIdStr, UUID kbIdForLog) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                AgentEntity fresh = agentRepository.findById(agentId).orElse(null);
                if (fresh == null || fresh.getKnowledgeBaseIds() == null) return false;
                if (!fresh.getKnowledgeBaseIds().remove(kbIdStr)) return false;
                agentRepository.save(fresh);
                return true;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                if (attempt == 3) {
                    log.warn("KB {} reference cleanup failed for agent {} after {} attempts; "
                            + "stale ref remains until next agent edit",
                            kbIdForLog, agentId, attempt);
                    return false;
                }
                log.debug("kb-cascade.cleanup.lock-retry agent={} kb={} attempt={}",
                        agentId, kbIdForLog, attempt);
            }
        }
        return false;
    }

    public List<AgentSummary> getAssignedAgents(UUID kbId) {
        return agentRepository.findByKnowledgeBaseIdsContaining(kbId.toString())
                .stream().map(AgentSummary::from).toList();
    }

    public void assignAgentToKb(UUID kbId, String agentId) {
        // Cross-tenant guard: the controller verifies caller owns the KB (id), but
        // pre-fix the service used findById on the agentId without any tenant check —
        // Org A could POST /api/v1/knowledge-bases/{A-kb}/agents/{B-agent} and attach
        // A's KB to B's agent, polluting B's RAG with A's content. Same exploit shape
        // as PR #998 (memory delete), PR #1007 (FinOps baseline), PR #1008 (quarantine).
        String callerOrgId = AgentContextHolder.getOrgId();
        AgentEntity agent = (callerOrgId != null && !callerOrgId.isBlank()
                ? agentRepository.findByIdAndOrgId(agentId, callerOrgId)
                : java.util.Optional.<AgentEntity>empty())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", agentId));
        if (agent.getKnowledgeBaseIds() == null) {
            agent.setKnowledgeBaseIds(new java.util.ArrayList<>());
        }
        String kbIdStr = kbId.toString();
        if (!agent.getKnowledgeBaseIds().contains(kbIdStr)) {
            agent.getKnowledgeBaseIds().add(kbIdStr);
            agentRepository.save(agent);
            log.info("Assigned KB {} to agent {}", kbId, agentId);
        }
    }

    public void removeAgentFromKb(UUID kbId, String agentId) {
        // Same cross-tenant guard as assignAgentToKb. Pre-fix, Org A admin could
        // POST DELETE /api/v1/knowledge-bases/{A-kb}/agents/{B-agent} which would
        // be a no-op (B's agent doesn't carry A's KB id), but symmetric defense is
        // important for the next attack class: if a prior exploit already added
        // A's KB to B's agent, the legitimate B's admin reading via /assigned-agents
        // would surface a cross-tenant binding A could then remove to cover tracks.
        String callerOrgId = AgentContextHolder.getOrgId();
        if (callerOrgId == null || callerOrgId.isBlank()) return;
        agentRepository.findByIdAndOrgId(agentId, callerOrgId).ifPresent(agent -> {
            if (agent.getKnowledgeBaseIds() != null && agent.getKnowledgeBaseIds().remove(kbId.toString())) {
                agentRepository.save(agent);
                log.info("Removed KB {} from agent {}", kbId, agentId);
            }
        });
    }
}
