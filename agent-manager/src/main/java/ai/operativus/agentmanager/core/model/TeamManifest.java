package ai.operativus.agentmanager.core.model;

import java.util.List;
import java.util.Map;

/**
 * Represents a Team's FinOps Budget and Capability boundaries,
 * defined strictly via configuration-as-code YAML (e.g., teams.yaml).
 */
public record TeamManifest(
    String teamId,
    String humanLead,
    Double maxDailySpend,
    Double minSpendingAuthority,
    List<String> allowedCapabilities,
    Map<String, AgentManifest> agents
) {
    public record AgentManifest(
        String agentId,
        String role,
        List<String> capabilities,
        Boolean requiresPiiRedaction
    ) {}
}
