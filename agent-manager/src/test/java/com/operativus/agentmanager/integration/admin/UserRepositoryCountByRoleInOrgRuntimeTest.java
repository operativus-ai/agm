package com.operativus.agentmanager.integration.admin;

import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.core.model.enums.RoleType;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins {@link UserRepository#countByRoleInOrg} — the
 *   last-admin guard's count source consumed by {@code UserAdminService.deleteUser}
 *   and {@code updateUser} to block "would leave the org with zero admins" operations.
 *
 *   <p>This is a security-critical query: a wrong count would either
 *   <ul>
 *     <li><b>under-count</b> → guard misfires positive when admins still exist →
 *         legit operations (deleting one of N admins, demoting one of N admins)
 *         silently rejected with "would leave org with no admins"</li>
 *     <li><b>over-count</b> → guard misfires negative when this IS the last admin →
 *         allows deletion / demotion → org is left with zero admins, locked out
 *         of admin-only operations forever</li>
 *   </ul>
 *
 *   <p>The query enforces THREE distinct constraints, each tested independently:
 *   <ol>
 *     <li>Role scope: only users with the requested {@link RoleType} are counted
 *         (a user with ROLE_USER must NOT be counted when asking for ROLE_ADMIN)</li>
 *     <li>Org scope: only users in the specified org are counted (cross-org users
 *         must NOT inflate the count)</li>
 *     <li><b>NULL-orgId branch</b>: the explicit
 *         {@code (:orgId IS NULL AND u.orgId IS NULL)} clause is required because
 *         SQL {@code u.orgId = NULL} is always false. Without it, callers passing
 *         {@code null} (legacy / system-tier admins) would always count 0 even
 *         when admins exist with NULL orgIds — the last-admin guard would then
 *         silently fail open.</li>
 *   </ol>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class UserRepositoryCountByRoleInOrgRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    // ════════════════════════════════════════════════════════════════
    // Role scope
    // ════════════════════════════════════════════════════════════════

    @Test
    void countByRoleInOrg_threeAdminsInOrg_returnsThree() {
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));

        assertEquals(3, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-A"));
    }

    @Test
    void countByRoleInOrg_usersWithDifferentRolesAreNotCounted() {
        // The guard asks specifically for ROLE_ADMIN. ROLE_USER and ROLE_VIEWER users
        // must NOT inflate the count — otherwise deleting an admin would be erroneously
        // blocked when ROLE_USER fills the "headcount".
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_USER)));
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_VIEWER)));
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_OPERATOR)));

        assertEquals(1, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-A"),
                "ONLY the ROLE_ADMIN user counts; other roles must be excluded");
    }

    @Test
    void countByRoleInOrg_userWithMultipleRolesCountsOnceForEachMatchingRole() {
        // A user with ROLE_ADMIN + ROLE_USER counts as 1 for ROLE_ADMIN, 1 for ROLE_USER.
        // Edge: the JPQL JOIN means each user is counted ONCE per matching role row, not
        // per all-roles-cartesian. Pin this so a future change to DISTINCT/grouping doesn't
        // silently change semantics.
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN, RoleType.ROLE_USER)));

        assertEquals(1, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-A"));
        assertEquals(1, userRepo.countByRoleInOrg(RoleType.ROLE_USER, "org-A"));
    }

    @Test
    void countByRoleInOrg_userWithNoMatchingRole_isNotCounted() {
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_USER, RoleType.ROLE_VIEWER)));
        assertEquals(0, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-A"));
    }

    // ════════════════════════════════════════════════════════════════
    // Org scope
    // ════════════════════════════════════════════════════════════════

    @Test
    void countByRoleInOrg_adminsInOtherOrgsAreNotCounted() {
        // CRITICAL security pin: 5 admins in org-B must NOT inflate the count for org-A,
        // because the last-admin guard for org-A is asking "are there still admins IN MY
        // ORG?" — cross-org admins are irrelevant.
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-B", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-B", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-B", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-B", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-B", Set.of(RoleType.ROLE_ADMIN)));

        assertEquals(1, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-A"),
                "org-B admins must NOT inflate the org-A count");
        assertEquals(5, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-B"),
                "org-B count must reflect org-B admins, not org-A");
    }

    @Test
    void countByRoleInOrg_unknownOrg_returnsZero() {
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));
        assertEquals(0, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-nonexistent"));
    }

    @Test
    void countByRoleInOrg_noUsersAtAll_returnsZero() {
        assertEquals(0, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-A"));
    }

    // ════════════════════════════════════════════════════════════════
    // NULL-orgId branch — the load-bearing legacy/system-tier path
    // ════════════════════════════════════════════════════════════════

    @Test
    void countByRoleInOrg_nullOrgIdMatchesUsersWithNullOrgId() {
        // CRITICAL pin: the (:orgId IS NULL AND u.orgId IS NULL) clause is required
        // because SQL `u.orgId = NULL` is always false. Without it, calling with
        // orgId=null would always count 0 — last-admin guards on legacy / system-tier
        // admins (orgId NULL) would silently fail open.
        userRepo.save(user(null, Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user(null, Set.of(RoleType.ROLE_ADMIN)));

        assertEquals(2, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, null),
                "null-orgId caller must match null-orgId users via explicit IS NULL branch");
    }

    @Test
    void countByRoleInOrg_nullOrgIdDoesNotMatchNonNullOrgIdUsers() {
        // Defensive: caller asking for null orgId must NOT pick up org-A admins.
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user(null, Set.of(RoleType.ROLE_ADMIN)));

        assertEquals(1, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, null),
                "null-orgId query must NOT include users with non-null orgId");
    }

    @Test
    void countByRoleInOrg_nonNullOrgIdDoesNotMatchNullOrgIdUsers() {
        // Defensive opposite: caller asking for org-A must NOT pick up null-orgId users.
        userRepo.save(user(null, Set.of(RoleType.ROLE_ADMIN)));
        userRepo.save(user("org-A", Set.of(RoleType.ROLE_ADMIN)));

        assertEquals(1, userRepo.countByRoleInOrg(RoleType.ROLE_ADMIN, "org-A"),
                "org-A query must NOT include null-orgId users");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static User user(String orgId, Set<RoleType> roles) {
        // User has @GeneratedValue(UUID); let Hibernate assign id.
        User u = new User();
        // Unique usernames + emails per row — username has unique constraint in many
        // Spring Security setups, and email is typically too.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        u.setUsername("test-user-" + suffix);
        u.setEmail("test-" + suffix + "@test.local");
        u.setPassword("password-irrelevant-for-count-query");
        u.setOrgId(orgId);
        u.setRoles(new HashSet<>(roles));
        return u;
    }
}
