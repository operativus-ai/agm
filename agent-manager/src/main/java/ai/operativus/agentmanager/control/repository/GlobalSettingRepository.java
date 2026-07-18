package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.GlobalSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Global Platform Configuration settings.
 * State: Stateless
 */
@Repository
public interface GlobalSettingRepository extends JpaRepository<GlobalSetting, String> {
}
