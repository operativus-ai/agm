package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.KnowledgeContent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for discrete parsed sections of ingested Knowledge Base files.
 * State: Stateless
 */
@Repository
public interface KnowledgeContentRepository extends JpaRepository<KnowledgeContent, UUID> {

    boolean existsByUri(String uri);

    boolean existsByContentHashAndKnowledgeBaseId(String contentHash, UUID knowledgeBaseId);

    Page<KnowledgeContent> findByKnowledgeBaseId(UUID knowledgeBaseId, Pageable pageable);

    List<KnowledgeContent> findByKnowledgeBaseIdIn(List<UUID> knowledgeBaseIds);

    long countByKnowledgeBaseId(UUID knowledgeBaseId);

    List<KnowledgeContent> findByOwnerId(String ownerId);

    @Query("SELECT kc FROM KnowledgeContent kc WHERE kc.knowledgeBaseId IN (SELECT kb.id FROM KnowledgeBase kb WHERE kb.orgId = :orgId)")
    Page<KnowledgeContent> findAllByCallerOrgId(@Param("orgId") String orgId, Pageable pageable);

    @Query("SELECT kc FROM KnowledgeContent kc WHERE kc.knowledgeBaseId = :kbId AND kc.knowledgeBaseId IN (SELECT kb.id FROM KnowledgeBase kb WHERE kb.orgId = :orgId)")
    Page<KnowledgeContent> findByKnowledgeBaseIdAndCallerOrgId(@Param("kbId") UUID kbId, @Param("orgId") String orgId, Pageable pageable);

    @Query("SELECT kc FROM KnowledgeContent kc WHERE kc.id = :id AND kc.knowledgeBaseId IN (SELECT kb.id FROM KnowledgeBase kb WHERE kb.orgId = :orgId)")
    java.util.Optional<KnowledgeContent> findByIdAndCallerOrgId(@Param("id") UUID id, @Param("orgId") String orgId);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE KnowledgeContent k SET k.accessCount = k.accessCount + 1 WHERE k.id IN (:ids)")
    void incrementAccessCount(@Param("ids") List<UUID> ids);
}
