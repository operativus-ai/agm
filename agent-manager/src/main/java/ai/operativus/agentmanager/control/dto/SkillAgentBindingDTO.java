package ai.operativus.agentmanager.control.dto;

/**
 * Domain Responsibility: Outbound wire shape for one agent ↔ skill attachment, served by
 * {@code GET /api/v1/skills/{skillId}/agents}. Carries the attached {@code agentId} and the
 * {@code priority} ordering (lower applies first). The UI resolves the agent's display name
 * from the agents list it already loads for the attach picker.
 * State: Stateless (Data Transfer Object)
 */
public record SkillAgentBindingDTO(
        String agentId,
        Integer priority
) {
}
