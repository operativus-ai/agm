package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.core.model.enums.RoleType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Human User Accounts.
 * State: Stateless
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Boolean existsByUsername(String username);

    Boolean existsByEmail(String email);

    Page<User> findAll(Pageable pageable);

    Optional<User> findByEmail(String email);

    /**
     * Counts users in the given tenant that carry the given role. Null orgId matches
     * other null-orgId users (legacy tenant-less bootstrap path). Used by
     * {@code UserAdminService} to enforce the last-admin guard on delete / role-revoke.
     */
    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r = :role AND " +
           "((:orgId IS NULL AND u.orgId IS NULL) OR u.orgId = :orgId)")
    long countByRoleInOrg(@Param("role") RoleType role, @Param("orgId") String orgId);
}
