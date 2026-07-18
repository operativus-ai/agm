package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.ProviderCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Persistence for {@link ProviderCredential}. Lookups are
 * tenant-scoped via the {@code (org_id, provider)} unique constraint.
 * State: Stateless.
 */
@Repository
public interface ProviderCredentialRepository extends JpaRepository<ProviderCredential, String> {

    Optional<ProviderCredential> findByOrgIdAndProvider(String orgId, String provider);

    List<ProviderCredential> findByOrgIdOrderByProvider(String orgId);
}
