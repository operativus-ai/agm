package ai.operativus.agentmanager.core.registry;

import ai.operativus.agentmanager.core.entity.User;
import java.util.Optional;

/**
 * Domain Responsibility: Registry contract to access core User definitions from the Compute plane.
 */
public interface UserProvider {
    Optional<User> findById(java.util.UUID id);
}
