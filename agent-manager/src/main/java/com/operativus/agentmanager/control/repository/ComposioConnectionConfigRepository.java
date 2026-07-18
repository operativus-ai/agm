package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.ComposioConnectionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Domain Responsibility: Persistence for per-org Composio connection IDs. Consumed by
 *   {@code ComposioConfigService} (PR2). UNIQUE constraint on {@code org_id} enforces
 *   "one connection per org" — {@code findByOrgId} returns at most one row.
 *
 * State: Stateless (Spring Data JPA proxy).
 */
@Repository
public interface ComposioConnectionConfigRepository extends JpaRepository<ComposioConnectionConfig, String> {

    /**
     * Lookup by orgId. UNIQUE constraint guarantees zero or one row per org.
     * Pairs with {@code application.properties} key
     * {@code agent.tools.composio.connection-ids.<orgId>=<id>} as the fallback path
     * when the DB has no row for the requesting org.
     */
    Optional<ComposioConnectionConfig> findByOrgId(String orgId);
}
