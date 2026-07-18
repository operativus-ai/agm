package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgentCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentCredentialRepository extends JpaRepository<AgentCredential, String> {
    List<AgentCredential> findByAgentId(String agentId);
    List<AgentCredential> findByAgentIdAndEnabledTrue(String agentId);
    List<AgentCredential> findByAgentIdAndProviderName(String agentId, String providerName);
    Optional<AgentCredential> findByIdAndAgentId(String id, String agentId);

    /**
     * @summary C01 quarantine: atomically disables every currently-enabled credential for
     *     {@code agentId}. Returns the IDs that were actually flipped so the audit row can
     *     record them — the unquarantine path uses that list to perform a TARGETED re-enable
     *     (NOT a blanket "enable all", which would re-enable manually-disabled credentials).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE agent_credentials
               SET enabled = false
             WHERE agent_id = :agentId
               AND enabled = true
            RETURNING id
            """, nativeQuery = true)
    List<String> disableByAgentId(@Param("agentId") String agentId);

    /**
     * @summary C01 unquarantine: re-enables exactly the credentials in {@code ids}. Used by
     *     {@code IncidentResponseService.unquarantineAgent} with the ID list parsed from the
     *     most-recent quarantine audit row's {@code changeset.locked_credentials} array.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE agent_credentials
               SET enabled = true
             WHERE id IN (:ids)
            """, nativeQuery = true)
    int enableByIds(@Param("ids") Collection<String> ids);
}
