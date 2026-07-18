package com.operativus.agentmanager.integration.team;

import com.operativus.agentmanager.control.repository.TeamRepository;
import com.operativus.agentmanager.core.entity.Team;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@link TeamRepository#findByOrgIdAndSearch} and
 *   {@link TeamRepository#findByArchivedAndOrgIdAndSearch} — the two text-search
 *   queries consumed by {@code TeamService} for the teams listing endpoint. Both
 *   queries use {@code LOWER(t.name) LIKE LOWER(CONCAT('%', :search, '%'))} on
 *   name OR description, scoped by orgId (and additionally by archived flag for
 *   the 4-param variant).
 *
 *   <p>Search-query bugs are hard to spot without targeted tests because the
 *   wrong-result symptom is "some matching team not shown" or "non-matching team
 *   surprisingly shown" — silent UI behavior, no error. Tested dimensions:
 *   <ul>
 *     <li>Match scope: name vs description (OR branch coverage)</li>
 *     <li>Case insensitivity: LOWER() on both sides means "foo" matches "FOO" and "FoO"</li>
 *     <li>Substring match: CONCAT('%', :search, '%') means partial matches work</li>
 *     <li>Org scope: cross-org teams must not surface</li>
 *     <li>Archived filter (4-param variant): archived=true returns archived ONLY;
 *         archived=false returns active ONLY</li>
 *     <li>Empty search: LIKE '%%' matches everything (intentional or accidental?
 *         — pinned as current behavior so future change is visible)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class TeamRepositorySearchRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private TeamRepository teamRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    // ════════════════════════════════════════════════════════════════
    // findByOrgIdAndSearch (3-param, archived-agnostic) — match-scope tests
    // ════════════════════════════════════════════════════════════════

    @Test
    void findByOrgIdAndSearch_matchesName() {
        teamRepo.save(team("Acme Engineering", "Backend work", "org-A", false));
        teamRepo.save(team("Sales", "Outbound", "org-A", false));

        Page<Team> page = teamRepo.findByOrgIdAndSearch("org-A", "Acme", PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements());
        assertEquals("Acme Engineering", page.getContent().get(0).getName());
    }

    @Test
    void findByOrgIdAndSearch_matchesDescription_viaOrBranch() {
        // The OR branch: description match alone is sufficient — name doesn't have to match.
        teamRepo.save(team("Engineering", "backend Java work", "org-A", false));
        teamRepo.save(team("Sales", "frontend pitch", "org-A", false));

        Page<Team> page = teamRepo.findByOrgIdAndSearch("org-A", "Java", PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements(),
                "description-only match must be returned via the OR branch");
        assertEquals("Engineering", page.getContent().get(0).getName());
    }

    @Test
    void findByOrgIdAndSearch_isCaseInsensitive() {
        // LOWER() on both sides means "MIXED-Case" search matches "mixed-CASE" team.
        teamRepo.save(team("Platform Team", "infrastructure work", "org-A", false));

        // Original casing
        assertEquals(1, teamRepo.findByOrgIdAndSearch("org-A", "Platform", PageRequest.of(0, 10)).getTotalElements());
        // ALL CAPS
        assertEquals(1, teamRepo.findByOrgIdAndSearch("org-A", "PLATFORM", PageRequest.of(0, 10)).getTotalElements());
        // all lowercase
        assertEquals(1, teamRepo.findByOrgIdAndSearch("org-A", "platform", PageRequest.of(0, 10)).getTotalElements());
        // Mixed
        assertEquals(1, teamRepo.findByOrgIdAndSearch("org-A", "pLaTfOrM", PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void findByOrgIdAndSearch_isSubstringMatch_notWordBoundary() {
        // CONCAT('%', :search, '%') means partial-string match — searching "form" matches
        // "platform" (not a word-boundary "form" search).
        teamRepo.save(team("Platform Team", "infrastructure", "org-A", false));

        assertEquals(1, teamRepo.findByOrgIdAndSearch("org-A", "form", PageRequest.of(0, 10)).getTotalElements(),
                "substring match: 'form' inside 'Platform' must match");
    }

    @Test
    void findByOrgIdAndSearch_emptyString_matchesEverything() {
        // CONCAT('%', '', '%') => LIKE '%%' which matches every non-null string.
        // This may or may not be intentional product behavior — pinning the CURRENT
        // behavior so a future change (e.g. "empty search returns nothing") is visible
        // as a deliberate flip, not a silent regression.
        teamRepo.save(team("Team A", "Desc A", "org-A", false));
        teamRepo.save(team("Team B", "Desc B", "org-A", false));
        teamRepo.save(team("Team C", "Desc C", "org-A", false));

        Page<Team> page = teamRepo.findByOrgIdAndSearch("org-A", "", PageRequest.of(0, 10));
        assertEquals(3, page.getTotalElements(),
                "empty-string search currently matches everything (LIKE '%%')");
    }

    @Test
    void findByOrgIdAndSearch_noMatch_returnsEmpty() {
        teamRepo.save(team("Team A", "Desc A", "org-A", false));

        Page<Team> page = teamRepo.findByOrgIdAndSearch("org-A", "nonexistent-substring", PageRequest.of(0, 10));
        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    // findByOrgIdAndSearch — cross-tenant isolation
    // ════════════════════════════════════════════════════════════════

    @Test
    void findByOrgIdAndSearch_crossOrgTeamsAreNotReturned() {
        // CRITICAL security pin: org-B's matching team must not surface in org-A's
        // search results — search is tenant-scoped via orgId.
        teamRepo.save(team("Platform Team", "infra A", "org-A", false));
        teamRepo.save(team("Platform Team", "infra B", "org-B", false));

        Page<Team> page = teamRepo.findByOrgIdAndSearch("org-A", "Platform", PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements(),
                "only org-A's matching team must be returned");
        assertEquals("infra A", page.getContent().get(0).getDescription());
    }

    @Test
    void findByOrgIdAndSearch_pageableHonored() {
        for (int i = 0; i < 7; i++) {
            teamRepo.save(team("Searchable-" + i, "desc", "org-A", false));
        }

        Page<Team> page0 = teamRepo.findByOrgIdAndSearch("org-A", "Searchable", PageRequest.of(0, 3));
        assertEquals(7, page0.getTotalElements(), "totalElements unaffected by page size");
        assertEquals(3, page0.getContent().size(), "first page returns 3 of 7");

        Page<Team> page2 = teamRepo.findByOrgIdAndSearch("org-A", "Searchable", PageRequest.of(2, 3));
        assertEquals(1, page2.getContent().size(), "third page returns the leftover 1");
    }

    @Test
    void findByOrgIdAndSearch_ignoresArchivedFlag_returnsBothArchivedAndActive() {
        // The 3-param variant has NO archived filter — pinned: it MUST return archived
        // teams alongside active ones in a single search. The 4-param variant is the one
        // that filters.
        teamRepo.save(team("Active Team", "active", "org-A", false));
        teamRepo.save(team("Archived Team", "archived", "org-A", true));

        Page<Team> page = teamRepo.findByOrgIdAndSearch("org-A", "Team", PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements(),
                "3-param variant must return BOTH archived and active teams");
    }

    // ════════════════════════════════════════════════════════════════
    // findByArchivedAndOrgIdAndSearch (4-param) — archived-filter tests
    // ════════════════════════════════════════════════════════════════

    @Test
    void findByArchivedAndOrgIdAndSearch_archivedFalse_returnsOnlyActive() {
        teamRepo.save(team("Active Team", "active", "org-A", false));
        teamRepo.save(team("Archived Team", "archived", "org-A", true));

        Page<Team> page = teamRepo.findByArchivedAndOrgIdAndSearch(false, "org-A", "Team", PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements(),
                "archived=false must filter OUT archived teams");
        assertEquals("Active Team", page.getContent().get(0).getName());
    }

    @Test
    void findByArchivedAndOrgIdAndSearch_archivedTrue_returnsOnlyArchived() {
        teamRepo.save(team("Active Team", "active", "org-A", false));
        teamRepo.save(team("Archived Team", "archived", "org-A", true));

        Page<Team> page = teamRepo.findByArchivedAndOrgIdAndSearch(true, "org-A", "Team", PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements(),
                "archived=true must return ONLY archived teams");
        assertEquals("Archived Team", page.getContent().get(0).getName());
    }

    @Test
    void findByArchivedAndOrgIdAndSearch_inheritsAllOtherFilters() {
        // The 4-param variant must still enforce orgId and the name/description search,
        // not just delegate to the archived filter.
        teamRepo.save(team("Active A", "desc", "org-A", false));
        teamRepo.save(team("Active B", "desc", "org-A", false));
        teamRepo.save(team("Active OtherOrg", "desc", "org-B", false)); // wrong org
        teamRepo.save(team("Skip This", "desc", "org-A", false));        // wrong name

        Page<Team> page = teamRepo.findByArchivedAndOrgIdAndSearch(false, "org-A", "Active",
                PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements(),
                "4-param variant must still apply orgId scope AND name search filter");
    }

    @Test
    void findByArchivedAndOrgIdAndSearch_caseInsensitiveSearchAlsoAppliesHere() {
        teamRepo.save(team("UPPER CASE TEAM", "desc", "org-A", false));

        assertEquals(1, teamRepo.findByArchivedAndOrgIdAndSearch(false, "org-A", "upper",
                PageRequest.of(0, 10)).getTotalElements());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static Team team(String name, String description, String orgId, boolean archived) {
        // Team uses String @Id (no @GeneratedValue); assign explicitly with UUID suffix
        // to avoid any cross-test id collisions.
        Team t = new Team();
        t.setId("team-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName(name);
        t.setDescription(description);
        t.setOrgId(orgId);
        t.setArchived(archived);
        return t;
    }
}
