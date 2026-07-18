package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.UserRepository;
import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.model.UserAdminDTO;
import ai.operativus.agentmanager.core.model.enums.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Domain Responsibility: Admin-level CRUD operations for user accounts and RBAC role assignment.
 * State: Stateless
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Page<User> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }

    @Transactional
    public User createUser(String username, String email, String rawPassword, Set<RoleType> roles, String callerOrgId) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessValidationException("Username already exists: " + username);
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessValidationException("Email already exists: " + email);
        }
        User user = new User(username, email, passwordEncoder.encode(rawPassword));
        user.setRoles(roles == null || roles.isEmpty() ? Set.of(RoleType.ROLE_VIEWER) : roles);
        // Stamp the creator's orgId onto the new row so admin-created users land in the
        // creator's tenant rather than tenant-orphaned. Pre-fix the column was always NULL
        // for /api/admin/users, which broke any downstream tenant-scoped query.
        user.setOrgId(callerOrgId);
        User saved = userRepository.save(user);
        log.info("Created user '{}' (org={}) with roles {}", username, callerOrgId, saved.getRoles());
        return saved;
    }

    @Transactional
    public User updateUser(UUID id, String email, Set<RoleType> roles, Boolean disabled, String callerOrgId) {
        User user = getUser(id);
        requireSameOrg(user, callerOrgId);
        if (email != null && !email.equals(user.getEmail())) {
            if (userRepository.existsByEmail(email)) {
                throw new BusinessValidationException("Email already exists: " + email);
            }
            user.setEmail(email);
        }
        if (roles != null) {
            boolean wasAdmin = user.getRoles() != null && user.getRoles().contains(RoleType.ROLE_ADMIN);
            boolean willBeAdmin = roles.contains(RoleType.ROLE_ADMIN);
            if (wasAdmin && !willBeAdmin) {
                requireNotLastAdminInOrg(user);
            }
            user.setRoles(roles);
        }
        if (disabled != null) {
            // Disabling the last remaining admin would lock the tenant out the same way
            // role-revocation does — block it by the same guard.
            boolean isAdmin = user.getRoles() != null && user.getRoles().contains(RoleType.ROLE_ADMIN);
            if (isAdmin && Boolean.TRUE.equals(disabled) && !user.isDisabled()) {
                requireNotLastAdminInOrg(user);
            }
            user.setDisabled(disabled);
        }
        log.info("Updated user '{}': disabled={}, roles={}", user.getUsername(), user.isDisabled(), user.getRoles());
        return userRepository.save(user);
    }

    @Transactional
    public void resetPassword(UUID id, String rawPassword, String callerOrgId) {
        User user = getUser(id);
        requireSameOrg(user, callerOrgId);
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
        log.info("Password reset for user '{}'", user.getUsername());
    }

    @Transactional
    public void deleteUser(UUID id, String callerOrgId, String callerUsername) {
        User user = getUser(id);
        requireSameOrg(user, callerOrgId);
        if (callerUsername != null && callerUsername.equals(user.getUsername())) {
            throw new BusinessValidationException("Cannot delete your own account");
        }
        if (user.getRoles() != null && user.getRoles().contains(RoleType.ROLE_ADMIN)) {
            requireNotLastAdminInOrg(user);
        }
        userRepository.delete(user);
        log.info("Deleted user '{}'", user.getUsername());
    }

    /**
     * Tenant-scope guard. Treats a cross-tenant attempt as a missing-resource (404) to
     * avoid leaking tenant membership — same pattern as ApprovalService and the other
     * org-scoped services. NULL orgIds match each other so legacy tenant-less admins
     * can still manage tenant-less users (e.g., the DEFAULT_SYSTEM_ORG bootstrap path).
     */
    /**
     * Throws BVE when {@code target} would be the last ADMIN in its tenant after the
     * pending mutation (delete, role-revoke, or disable). Counts admins via
     * {@link UserRepository#countByRoleInOrg} so disabled-but-still-admin users are
     * counted too — the lock-out condition is "no enabled admin left who can re-enable",
     * which is a stricter check than "no admin role left", and we conservatively block
     * either case under the same single-guard.
     */
    private void requireNotLastAdminInOrg(User target) {
        long adminCount = userRepository.countByRoleInOrg(RoleType.ROLE_ADMIN, target.getOrgId());
        if (adminCount <= 1) {
            throw new BusinessValidationException(
                    "Cannot leave tenant without an admin: '" + target.getUsername()
                            + "' is the last ROLE_ADMIN in tenant "
                            + (target.getOrgId() == null ? "(legacy null org)" : target.getOrgId()));
        }
    }

    private void requireSameOrg(User target, String callerOrgId) {
        String targetOrgId = target.getOrgId();
        boolean sameOrg = (targetOrgId == null && callerOrgId == null)
                || (targetOrgId != null && targetOrgId.equals(callerOrgId));
        if (!sameOrg) {
            throw new ResourceNotFoundException("User", target.getId().toString());
        }
    }

    /**
     * Bulk-creates users with per-item idempotency. For each item, if the username already
     * exists the row is reported as {@code already_exists} (no update, no throw); otherwise
     * a new user is created exactly like {@link #createUser}. Resubmitting the same payload
     * converges on the same final state — §23 case 6.
     *
     * <p>Email collisions against a different username still throw
     * {@link BusinessValidationException} for that item so callers don't accidentally alias
     * two usernames to a single email.</p>
     */
    @Transactional
    public UserAdminDTO.BulkCreateResponse bulkCreate(List<UserAdminDTO.CreateRequest> requests, String callerOrgId) {
        List<UserAdminDTO.BulkCreateItem> items = new ArrayList<>(requests.size());
        int created = 0;
        int alreadyExisted = 0;
        for (UserAdminDTO.CreateRequest req : requests) {
            Optional<User> existing = userRepository.findByUsername(req.username());
            if (existing.isPresent()) {
                User u = existing.get();
                if (req.email() != null && !req.email().equals(u.getEmail())) {
                    throw new BusinessValidationException(
                            "Bulk create conflict for '" + req.username()
                                    + "': existing user has a different email than the resubmitted row");
                }
                items.add(new UserAdminDTO.BulkCreateItem(u.getId(), u.getUsername(), u.getEmail(),
                        UserAdminDTO.BulkCreateItem.STATUS_ALREADY_EXISTS));
                alreadyExisted++;
                continue;
            }
            if (userRepository.existsByEmail(req.email())) {
                throw new BusinessValidationException(
                        "Bulk create conflict: email already bound to another user: " + req.email());
            }
            User user = new User(req.username(), req.email(), passwordEncoder.encode(req.password()));
            user.setRoles(req.roles() == null || req.roles().isEmpty()
                    ? Set.of(RoleType.ROLE_VIEWER) : req.roles());
            // Same orgId-stamping as createUser — bulk-created users land in the caller's tenant.
            user.setOrgId(callerOrgId);
            User saved = userRepository.save(user);
            items.add(new UserAdminDTO.BulkCreateItem(saved.getId(), saved.getUsername(), saved.getEmail(),
                    UserAdminDTO.BulkCreateItem.STATUS_CREATED));
            created++;
        }
        log.info("Bulk-create users (org={}): {} created, {} already existed (total {})",
                callerOrgId, created, alreadyExisted, requests.size());
        return new UserAdminDTO.BulkCreateResponse(items, created, alreadyExisted);
    }
}
