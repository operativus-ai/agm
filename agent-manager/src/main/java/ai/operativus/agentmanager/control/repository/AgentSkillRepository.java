package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval of Agent ↔ Skill
 *     attachments. Ordered finder is the canonical entry point for SkillInjector;
 *     priority ASC then created_at ASC produces a deterministic tool/snippet
 *     application order.
 * State: Stateless
 */
@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkill, AgentSkill.AgentSkillId> {

    List<AgentSkill> findByAgentIdOrderByPriorityAscCreatedAtAsc(String agentId);

    List<AgentSkill> findBySkillIdOrderByPriorityAscCreatedAtAsc(String skillId);

    boolean existsByAgentIdAndSkillId(String agentId, String skillId);

    void deleteByAgentIdAndSkillId(String agentId, String skillId);

    void deleteAllBySkillId(String skillId);

    long countByAgentId(String agentId);
}
