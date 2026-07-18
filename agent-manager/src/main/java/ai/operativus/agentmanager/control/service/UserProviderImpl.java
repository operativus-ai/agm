package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.UserRepository;
import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.core.registry.UserProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Domain Responsibility: Provides user entity lookup capabilities for internal service components.
 * State: Stateless
 */
@Service
public class UserProviderImpl implements UserProvider {
    private final UserRepository userRepository;

    public UserProviderImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @summary Retrieves a user by their UUID.
     * @logic Delegates to the UserRepository to find the user by ID.
     */
    @Override
    public Optional<User> findById(java.util.UUID id) {
        return userRepository.findById(id);
    }
}
