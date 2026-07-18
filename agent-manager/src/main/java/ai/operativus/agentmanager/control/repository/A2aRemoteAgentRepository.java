package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.A2aRemoteAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Persistence operations for registered remote A2A peer agents.
 * Backs the {@code a2a_remote_agents} table which stores external AGM peers that
 * this instance is authorized to delegate tasks to.
 *
 * State: Stateless (Repository Interface)
 */
@Repository
public interface A2aRemoteAgentRepository extends JpaRepository<A2aRemoteAgentEntity, String> {

    /**
     * @summary Looks up a peer registration by its local friendly alias.
     * @logic Most common lookup path in {@code A2ACardResolver} — alias is the primary
     *        routing key used by orchestrators when naming a remote delegation target.
     */
    Optional<A2aRemoteAgentEntity> findByAlias(String alias);

    /**
     * @summary Looks up a peer registration by the remote agent's declared ID.
     * @logic Fallback path when the caller uses the remote agent's own ID rather than
     *        the local alias. Used by {@code A2ACardResolver.findRegistrationFor()}.
     */
    Optional<A2aRemoteAgentEntity> findByRemoteAgentId(String remoteAgentId);

    /**
     * @summary Returns all active (trusted) peer registrations.
     * @logic Used at startup to prime the in-memory registry cache in {@code A2ACardResolver}.
     */
    List<A2aRemoteAgentEntity> findByTrustedTrue();

    /**
     * @summary Checks whether a peer with the given alias already exists.
     * @logic Used to prevent duplicate alias registration.
     */
    boolean existsByAlias(String alias);

    /**
     * @summary Org-scoped alias lookup — §22.7 cross-org isolation.
     * @logic UNIQUE(org_id, alias) is enforced at the DB layer so this returns at
     *        most one row per (org, alias) tuple. Pass a null orgId to match legacy
     *        rows registered before the org_id column existed.
     */
    Optional<A2aRemoteAgentEntity> findByAliasAndOrgId(String alias, String orgId);

    /**
     * @summary Org-scoped remote_agent_id lookup — §22.5 follow-on duplicate-peer guard.
     * @logic UNIQUE(org_id, remote_agent_id) (changeset 064) is enforced at the DB
     *        layer so this returns at most one row per (org, remoteAgentId) tuple.
     *        Used by {@code A2AController.registerPeer} to surface duplicate registrations
     *        as 409 Conflict before they hit the DB constraint. Different orgs may still
     *        register the same upstream remoteAgentId — only same-org duplicates are barred.
     */
    Optional<A2aRemoteAgentEntity> findByRemoteAgentIdAndOrgId(String remoteAgentId, String orgId);

    /**
     * @summary Returns all peer registrations for a given org.
     * @logic Backs {@code A2ACardResolver.listRemoteRegistrations(orgId)} and the
     *        {@code GET /api/v1/a2a/peers} endpoint when scoped by {@code X-Org-Id}.
     */
    List<A2aRemoteAgentEntity> findByOrgId(String orgId);

    /**
     * @summary Returns peer rows whose stored key_version differs from the active version.
     * @logic §22.6 key rotation. The scheduled {@code OutboundApiKeyMigrationService}
     *        selects rotation candidates with this query, then re-saves each row to force
     *        the converter to re-encrypt under the active key version.
     */
    List<A2aRemoteAgentEntity> findByKeyVersionNot(Integer keyVersion);
}
