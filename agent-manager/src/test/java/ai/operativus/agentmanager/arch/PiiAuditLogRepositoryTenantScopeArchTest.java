package ai.operativus.agentmanager.arch;

import ai.operativus.agentmanager.compute.security.PiiAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Forward guard that every derived-query finder declared on
 *   {@link PiiAuditLogRepository} is org-scoped. {@code pii_audit_log} holds cross-tenant
 *   PII-scrub history; the {@code org_id} predicate is the tenant-isolation boundary, so an
 *   unscoped finder (e.g. {@code findByAgentId(...)} / {@code findBySessionId(...)}) is an
 *   IDOR footgun — a future caller could reach for it instead of the org-scoped variant and
 *   leak one org's redaction history to another. Two such unscoped, uncalled finders were
 *   removed in the same change that added this guard; this test stops them (or new ones)
 *   from coming back silently.
 *
 *   <p>A finder passes if its name carries {@code OrgId} OR it is a {@code @Query} whose text
 *   references {@code org_id}/{@code orgId}. Genuinely org-agnostic methods (none today) must
 *   be added to {@link #ORG_AGNOSTIC_ALLOWLIST} with a rationale — the allowlist ratchets down.
 *
 * State: Stateless. Pure-classpath unit test — references the interface directly (no scan).
 *   Sibling to {@link ControllerPreAuthorizeArchTest}, same ratchet-down semantics.
 */
public class PiiAuditLogRepositoryTenantScopeArchTest {

    /** Derived-query prefixes (Spring Data) that hit the DB and therefore need tenant scoping. */
    private static final Set<String> QUERY_PREFIXES =
            Set.of("find", "read", "get", "query", "search", "stream", "count", "exists", "delete", "remove");

    /**
     * Finder names on {@link PiiAuditLogRepository} that are intentionally NOT org-scoped.
     * Empty today. Adding an entry requires a documented rationale in the PR — an unscoped
     * finder on a cross-tenant PII table is an IDOR footgun by default.
     */
    private static final Set<String> ORG_AGNOSTIC_ALLOWLIST = Set.of();

    @Test
    void everyDeclaredFinder_isOrgScoped() {
        TreeSet<String> violations = new TreeSet<>();
        for (Method m : PiiAuditLogRepository.class.getDeclaredMethods()) {
            String name = m.getName();
            if (QUERY_PREFIXES.stream().noneMatch(name::startsWith)) continue; // default/helper methods
            if (ORG_AGNOSTIC_ALLOWLIST.contains(name)) continue;
            if (name.contains("OrgId")) continue;
            Query q = m.getAnnotation(Query.class);
            if (q != null) {
                String ql = q.value().toLowerCase();
                if (ql.contains("org_id") || ql.contains("orgid")) continue;
            }
            violations.add(name);
        }
        if (!violations.isEmpty()) {
            fail("Unscoped finder(s) on PiiAuditLogRepository (IDOR risk — pii_audit_log is cross-tenant):\n"
                    + "  " + violations + "\n"
                    + "  Every finder must constrain org_id (name contains 'OrgId', or a @Query referencing org_id).\n"
                    + "  Use findByOrgIdOrderByCreatedAtDesc / findByOrgIdAndAgentIdOrderByCreatedAtDesc, or — only\n"
                    + "  with a documented rationale — add the method name to ORG_AGNOSTIC_ALLOWLIST.");
        }
    }
}
