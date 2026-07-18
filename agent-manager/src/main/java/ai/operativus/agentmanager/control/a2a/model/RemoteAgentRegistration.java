package ai.operativus.agentmanager.control.a2a.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Domain Responsibility: Immutable value object representing a remote A2A peer agent
 * registration — the persisted record of an external agent AGM has discovered or been
 * configured to trust.
 *
 * Gap 2.1: Stored in {@code a2a_remote_agents} and used by {@code A2ACardResolver}
 * to route inter-platform delegation requests. The {@code lastResolvedCard} field is
 * a cached snapshot refreshed on each successful card fetch.
 *
 * @param id               Internal UUID primary key.
 * @param remoteAgentId    Identifier declared by the remote agent in its AgentCard.
 * @param baseUrl          Root URL of the remote AGM or A2A-compatible peer instance.
 * @param alias            Friendly alias used by local orchestrators when referencing this peer.
 * @param trustedApiKey    Outbound API key sent in {@code X-A2A-Api-Key} when calling the remote.
 * @param lastResolvedCard The most recently fetched {@link AgentCard} for this peer (may be null).
 * @param registeredAt     When this peer was first registered.
 * @param lastVerifiedAt   When the card was last successfully fetched (null if never verified).
 */
public record RemoteAgentRegistration(
    String id,
    String remoteAgentId,
    String baseUrl,
    String alias,
    @JsonIgnore String trustedApiKey,
    AgentCard lastResolvedCard,
    Instant registeredAt,
    Instant lastVerifiedAt
) {}
