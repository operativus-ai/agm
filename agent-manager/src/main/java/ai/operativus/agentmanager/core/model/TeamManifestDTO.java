package ai.operativus.agentmanager.core.model;

import java.util.Map;
import java.util.List;

public record TeamManifestDTO(
    String teamId,
    String humanLead,
    double maxDailySpend,
    double minSpendingAuthority,
    List<String> allowedCapabilities,
    Map<String, AgentRoleDTO> agents
) {
    public record AgentRoleDTO(
        String agentId,
        String role,
        List<String> capabilities,
        boolean requiresPiiRedaction
    ) {}
}
