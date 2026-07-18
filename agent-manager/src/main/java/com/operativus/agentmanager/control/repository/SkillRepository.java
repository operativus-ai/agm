package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.Skill;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Skill
 *     definitions. All queries are tenant-scoped via orgId; cross-tenant access is
 *     not possible through this repository.
 * State: Stateless
 */
@Repository
public interface SkillRepository extends JpaRepository<Skill, String> {

    Page<Skill> findAllByOrgId(String orgId, Pageable pageable);

    Optional<Skill> findByIdAndOrgId(String id, String orgId);

    Optional<Skill> findByOrgIdAndName(String orgId, String name);

    List<Skill> findAllByOrgIdAndActiveTrue(String orgId);

    boolean existsByIdAndOrgId(String id, String orgId);

    boolean existsByOrgIdAndName(String orgId, String name);

    @Query("SELECT s FROM Skill s, AgentSkill a WHERE a.skillId = s.id AND a.agentId = :agentId AND s.active = true ORDER BY a.priority ASC, a.createdAt ASC")
    List<Skill> findActiveSkillsForAgent(@Param("agentId") String agentId);
}
