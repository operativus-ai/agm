package ai.operativus.agentmanager.integration.knowledge;

import ai.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import ai.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import ai.operativus.agentmanager.core.entity.KnowledgeBase;
import ai.operativus.agentmanager.core.entity.KnowledgeContent;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the tenant-isolation guarantees of
 *   {@link KnowledgeContentRepository#findByKnowledgeBaseIdAndCallerOrgId} and
 *   {@link KnowledgeContentRepository#findByIdAndCallerOrgId}. Both queries enforce
 *   the org boundary via a subquery — {@code kc.knowledgeBaseId IN (SELECT kb.id FROM
 *   KnowledgeBase kb WHERE kb.orgId = :orgId)} — because {@code KnowledgeContent} has
 *   no direct {@code org_id} column. The orgId lives on the parent
 *   {@link KnowledgeBase}.
 *
 *   <p>Without these tests, a refactor that drops the subquery, broadens it (e.g. uses
 *   {@code OR} instead of {@code AND}), or accidentally compares against the wrong
 *   field would silently leak cross-tenant knowledge content. Worse: there'd be no
 *   error surface — the caller would just get rows they shouldn't see, and downstream
 *   admin pages would render foreign-tenant data alongside their own.
 *
 *   <p>This is the exact bug class the security campaign earlier this session closed
 *   at the controller/service layer (PR #1019 mass-assignment, #1008/#1009 service-layer
 *   guards). These tests close it at the repository layer too — the deepest safety net.
 *
 *   <p>Sibling query {@code findAllByCallerOrgId} is already covered by
 *   {@code PageableServiceTest.KnowledgeServicePaginationTests} (via {@code KnowledgeService.listFiles})
 *   so this test focuses on the two naked siblings flagged by the round-2 repo audit.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class KnowledgeContentRepositoryCrossTenantRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private KnowledgeContentRepository contentRepo;
    @Autowired
    private KnowledgeBaseRepository kbRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    // ════════════════════════════════════════════════════════════════
    // findByKnowledgeBaseIdAndCallerOrgId
    // ════════════════════════════════════════════════════════════════

    @Test
    void findByKnowledgeBaseIdAndCallerOrgId_sameOrgSameKb_returnsContent() {
        KnowledgeBase kbA = seedKb("org-A", "kb-A");
        contentRepo.save(content(kbA.getId(), "doc1.pdf"));
        contentRepo.save(content(kbA.getId(), "doc2.pdf"));

        Page<KnowledgeContent> result = contentRepo.findByKnowledgeBaseIdAndCallerOrgId(
                kbA.getId(), "org-A", PageRequest.of(0, 10));

        assertEquals(2, result.getTotalElements(),
                "same-org caller must see both content rows in their KB");
    }

    @Test
    void findByKnowledgeBaseIdAndCallerOrgId_crossOrgCallerWithForeignKbId_returnsEmpty() {
        // CRITICAL security pin: orgA caller passes orgB's kbId — must get nothing.
        // The subquery scopes by orgId, so the kbId from org-B isn't in the candidate set.
        KnowledgeBase kbB = seedKb("org-B", "kb-B");
        contentRepo.save(content(kbB.getId(), "secret-doc.pdf"));

        Page<KnowledgeContent> result = contentRepo.findByKnowledgeBaseIdAndCallerOrgId(
                kbB.getId(), "org-A", PageRequest.of(0, 10));

        assertEquals(0, result.getTotalElements(),
                "org-A caller targeting org-B's kbId must get ZERO rows — cross-tenant leak");
        assertTrue(result.getContent().isEmpty(),
                "leak via empty page content list");
    }

    @Test
    void findByKnowledgeBaseIdAndCallerOrgId_kbIdMismatchedToOrg_returnsEmpty() {
        // Edge case: caller has a valid orgId BUT the kbId belongs to a different KB
        // in the same caller's org (just not the one they think). Result: empty because
        // the AND clause filters out the wrong kbId.
        KnowledgeBase kbA1 = seedKb("org-A", "kb-A-1");
        KnowledgeBase kbA2 = seedKb("org-A", "kb-A-2");
        contentRepo.save(content(kbA1.getId(), "in-kb-1.pdf"));
        contentRepo.save(content(kbA2.getId(), "in-kb-2.pdf"));

        Page<KnowledgeContent> result = contentRepo.findByKnowledgeBaseIdAndCallerOrgId(
                kbA1.getId(), "org-A", PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements(),
                "AND filter must restrict to the named kbId even within the same org");
        assertEquals("in-kb-1.pdf", result.getContent().get(0).getName());
    }

    @Test
    void findByKnowledgeBaseIdAndCallerOrgId_unknownKbId_returnsEmpty() {
        seedKb("org-A", "kb-A");
        Page<KnowledgeContent> result = contentRepo.findByKnowledgeBaseIdAndCallerOrgId(
                UUID.randomUUID(), "org-A", PageRequest.of(0, 10));
        assertEquals(0, result.getTotalElements(),
                "unknown kbId must return empty, not error");
    }

    @Test
    void findByKnowledgeBaseIdAndCallerOrgId_unknownOrgId_returnsEmpty() {
        // The subquery's WHERE clause finds no KB rows for "org-nonexistent" → outer
        // query's IN candidate set is empty → no content rows match.
        KnowledgeBase kbA = seedKb("org-A", "kb-A");
        contentRepo.save(content(kbA.getId(), "doc.pdf"));

        Page<KnowledgeContent> result = contentRepo.findByKnowledgeBaseIdAndCallerOrgId(
                kbA.getId(), "org-nonexistent", PageRequest.of(0, 10));
        assertEquals(0, result.getTotalElements());
    }

    @Test
    void findByKnowledgeBaseIdAndCallerOrgId_pageableHonored() {
        KnowledgeBase kbA = seedKb("org-A", "kb-A");
        for (int i = 0; i < 7; i++) {
            contentRepo.save(content(kbA.getId(), "doc-" + i + ".pdf"));
        }

        Page<KnowledgeContent> page0 = contentRepo.findByKnowledgeBaseIdAndCallerOrgId(
                kbA.getId(), "org-A", PageRequest.of(0, 3));
        Page<KnowledgeContent> page1 = contentRepo.findByKnowledgeBaseIdAndCallerOrgId(
                kbA.getId(), "org-A", PageRequest.of(1, 3));

        assertEquals(7, page0.getTotalElements());
        assertEquals(3, page0.getContent().size());
        assertEquals(3, page1.getContent().size(), "second page also returns 3 of 7");
    }

    // ════════════════════════════════════════════════════════════════
    // findByIdAndCallerOrgId
    // ════════════════════════════════════════════════════════════════

    @Test
    void findByIdAndCallerOrgId_sameOrg_returnsContent() {
        KnowledgeBase kbA = seedKb("org-A", "kb-A");
        KnowledgeContent saved = contentRepo.save(content(kbA.getId(), "doc.pdf"));

        Optional<KnowledgeContent> result = contentRepo.findByIdAndCallerOrgId(saved.getId(), "org-A");

        assertTrue(result.isPresent(), "same-org caller must retrieve their content");
        assertEquals("doc.pdf", result.get().getName());
    }

    @Test
    void findByIdAndCallerOrgId_crossOrgCallerWithForeignContentId_returnsEmpty() {
        // CRITICAL security pin: orgA caller knows orgB's content ID (somehow — leaked
        // via logs, URL guessing, etc.) and tries to fetch it directly. Must get Optional.empty().
        KnowledgeBase kbB = seedKb("org-B", "kb-B");
        KnowledgeContent secret = contentRepo.save(content(kbB.getId(), "secret-doc.pdf"));

        Optional<KnowledgeContent> result = contentRepo.findByIdAndCallerOrgId(secret.getId(), "org-A");

        assertFalse(result.isPresent(),
                "org-A caller must NOT be able to fetch org-B's content by direct ID — cross-tenant IDOR");
    }

    @Test
    void findByIdAndCallerOrgId_unknownContentId_returnsEmpty() {
        seedKb("org-A", "kb-A");
        Optional<KnowledgeContent> result = contentRepo.findByIdAndCallerOrgId(UUID.randomUUID(), "org-A");
        assertFalse(result.isPresent(),
                "unknown contentId must return empty Optional, not error");
    }

    @Test
    void findByIdAndCallerOrgId_unknownOrgId_returnsEmpty() {
        KnowledgeBase kbA = seedKb("org-A", "kb-A");
        KnowledgeContent saved = contentRepo.save(content(kbA.getId(), "doc.pdf"));

        Optional<KnowledgeContent> result = contentRepo.findByIdAndCallerOrgId(saved.getId(), "org-nonexistent");

        assertFalse(result.isPresent(),
                "unknown orgId scopes subquery to empty KB candidate set → no content match");
    }

    @Test
    void findByIdAndCallerOrgId_threeOrgsThreeContents_eachCallerOnlySeesOwn() {
        // Defensive multi-tenant pin: ensure no row from any other org leaks under any
        // permutation of (callerOrgId, contentId). 3 orgs × 3 contents = 9 lookup permutations;
        // only the 3 same-org pairs must return non-empty.
        KnowledgeBase kbA = seedKb("org-A", "kb-A");
        KnowledgeBase kbB = seedKb("org-B", "kb-B");
        KnowledgeBase kbC = seedKb("org-C", "kb-C");
        KnowledgeContent contentA = contentRepo.save(content(kbA.getId(), "a.pdf"));
        KnowledgeContent contentB = contentRepo.save(content(kbB.getId(), "b.pdf"));
        KnowledgeContent contentC = contentRepo.save(content(kbC.getId(), "c.pdf"));

        // Same-org happy paths (3)
        assertTrue(contentRepo.findByIdAndCallerOrgId(contentA.getId(), "org-A").isPresent());
        assertTrue(contentRepo.findByIdAndCallerOrgId(contentB.getId(), "org-B").isPresent());
        assertTrue(contentRepo.findByIdAndCallerOrgId(contentC.getId(), "org-C").isPresent());

        // All 6 cross-org permutations (3 callers × 2 other-org contents each) must be empty
        assertFalse(contentRepo.findByIdAndCallerOrgId(contentA.getId(), "org-B").isPresent());
        assertFalse(contentRepo.findByIdAndCallerOrgId(contentA.getId(), "org-C").isPresent());
        assertFalse(contentRepo.findByIdAndCallerOrgId(contentB.getId(), "org-A").isPresent());
        assertFalse(contentRepo.findByIdAndCallerOrgId(contentB.getId(), "org-C").isPresent());
        assertFalse(contentRepo.findByIdAndCallerOrgId(contentC.getId(), "org-A").isPresent());
        assertFalse(contentRepo.findByIdAndCallerOrgId(contentC.getId(), "org-B").isPresent());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private KnowledgeBase seedKb(String orgId, String name) {
        // NOTE: don't pre-set id — KnowledgeBase has @GeneratedValue(AUTO). Setting id
        // makes save() merge-style (UPDATE on non-existent row) which fails with
        // StaleObjectStateException.
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setOrgId(orgId);
        kb.setOwnerId("test-user");
        return kbRepo.save(kb);
    }

    private static KnowledgeContent content(UUID kbId, String name) {
        // Same: KnowledgeContent uses @GeneratedValue; let Hibernate assign id.
        KnowledgeContent kc = new KnowledgeContent();
        kc.setName(name);
        kc.setKnowledgeBaseId(kbId);
        kc.setStatus(RunStatus.COMPLETED);
        return kc;
    }
}
