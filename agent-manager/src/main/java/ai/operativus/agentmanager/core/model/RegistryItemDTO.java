package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Acts as an immutable data transfer object providing a summarized view of a registered Agent or Team for discovery purposes.
 * State: Stateless (Immutable Record carrier)
 */
public record RegistryItemDTO(
        String id,
        String name,
        String description,
        String itemType // "AGENT" or "TEAM"
) {}
