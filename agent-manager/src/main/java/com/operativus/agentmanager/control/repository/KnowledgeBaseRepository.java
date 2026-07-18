package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Agent Knowledge Bases.
 * State: Stateless
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    Optional<KnowledgeBase> findByName(String name);

    java.util.List<KnowledgeBase> findByOwnerId(String ownerId);

    // Tenant-scoped finders. Used by KnowledgeBaseController + KnowledgeService to enforce
    // org isolation; the unscoped findById/findByName/findAll above remain for system-context
    // callers (background jobs, erasure handlers).
    java.util.List<KnowledgeBase> findByOrgId(String orgId);

    Optional<KnowledgeBase> findByIdAndOrgId(UUID id, String orgId);

    Optional<KnowledgeBase> findByNameAndOrgId(String name, String orgId);

    boolean existsByIdAndOrgId(UUID id, String orgId);
}
