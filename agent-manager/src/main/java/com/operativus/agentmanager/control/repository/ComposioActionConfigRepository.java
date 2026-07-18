package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.ComposioActionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Persistence for DB-backed Composio action configuration. Consumed by
 *   {@code ComposioConfigService} (PR2 of the Composio admin stack); service layer translates
 *   between this repository and {@code ComposioActionRegistry} via the
 *   {@code ApplicationEventPublisher} re-bind path.
 *
 * State: Stateless (Spring Data JPA proxy).
 */
@Repository
public interface ComposioActionConfigRepository extends JpaRepository<ComposioActionConfig, String> {

    /**
     * Returns enabled rows in deterministic order — used by {@code ComposioActionRegistry}
     * to build the per-boot callback list. Ordering keeps the registered-callback set
     * deterministic across restarts (no test flakes from set-iteration order).
     */
    List<ComposioActionConfig> findByEnabledTrueOrderByActionName();

    /**
     * Lookup by canonical action name (e.g. {@code GMAIL_FETCH_EMAILS}). UNIQUE constraint
     * on the column means {@code Optional} contains zero or one row.
     */
    Optional<ComposioActionConfig> findByActionName(String actionName);
}
