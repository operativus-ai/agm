package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain Responsibility: Join entity binding an Agent to a Skill with a priority
 *     ordering. Lower priority number applies first (Unix nice convention).
 *     SkillInjector iterates attachments in {@code priority ASC, created_at ASC}
 *     order so tool dedup is deterministic. ON DELETE CASCADE on {@code skill_id}
 *     (DB-level) cleans up attachments when a skill is dropped.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "agent_skills")
@IdClass(AgentSkill.AgentSkillId.class)
public class AgentSkill {

    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Id
    @Column(name = "skill_id")
    private String skillId;

    @Column(nullable = false)
    private Integer priority = 100;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AgentSkill() {}

    public AgentSkill(String agentId, String skillId, Integer priority) {
        this.agentId = agentId;
        this.skillId = skillId;
        this.priority = priority != null ? priority : 100;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public static class AgentSkillId implements Serializable {
        private String agentId;
        private String skillId;

        public AgentSkillId() {}

        public AgentSkillId(String agentId, String skillId) {
            this.agentId = agentId;
            this.skillId = skillId;
        }

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }

        public String getSkillId() { return skillId; }
        public void setSkillId(String skillId) { this.skillId = skillId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AgentSkillId that)) return false;
            return Objects.equals(agentId, that.agentId) && Objects.equals(skillId, that.skillId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agentId, skillId);
        }
    }
}
