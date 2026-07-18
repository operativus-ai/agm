package com.operativus.agentmanager.compute.advisor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Domain Responsibility: Pins the relative ordering of advisors in
 * {@code compute/advisor/} against the PII redaction boundary at
 * {@link PIIAnonymizationAdvisor#getOrder()} (= 10). This test exists because
 * advisor order numbers are unconstrained {@code int}s with no compiler enforcement,
 * and audit findings F11 / F12 each surfaced an advisor that read
 * {@code request.prompt().getContents()} from a position before the PII redactor —
 * leaking unredacted user content into the embedding API + cache row (F11) or
 * outbound webhook POST bodies (F12). The fix in both cases was to push the advisor
 * past order 10.
 *
 * State: Stateless
 *
 * <p><b>How to extend.</b> When adding a new advisor in {@code compute/advisor/}:
 * <ol>
 *   <li>If the advisor reads {@code request.prompt().getContents()} or otherwise
 *       inspects raw user content, add it to {@link #advisorsAfterPiiBoundary} so
 *       its order is asserted &gt;= 10.</li>
 *   <li>If the advisor must run before order 10 for legitimate reasons (e.g., a
 *       defensive content scanner that throws without leaking the matched text),
 *       add it to {@link #advisorsBeforePiiBoundary_documentedAsNonLeaking} with a
 *       short comment justifying why the early read is safe.</li>
 * </ol>
 *
 * <p><b>Boundary value.</b> The constant {@link #PII_BOUNDARY_ORDER} is the order
 * at which {@link PIIAnonymizationAdvisor} mutates the request to substitute the
 * redacted prompt. Any advisor running at order &gt;= 10 sees redacted text via
 * {@code request.prompt().getContents()}. Any advisor running &lt; 10 sees raw text.
 */
class AdvisorPiiBoundaryContractTest {

    /**
     * Order at which {@code PIIAnonymizationAdvisor.adviseCall} mutates the request
     * to carry the redacted prompt. Hardcoded here (rather than read from the bean)
     * so this test pins the value — changing the boundary requires conscious update
     * of every advisor's relative position.
     */
    static final int PII_BOUNDARY_ORDER = 10;

    @Test
    void piiAnonymizationAdvisor_definesTheBoundary() {
        PIIAnonymizationAdvisor advisor = new PIIAnonymizationAdvisor(
                mock(com.operativus.agentmanager.compute.security.PiiPolicyService.class),
                mock(com.operativus.agentmanager.compute.security.DeterministicNEREngine.class),
                mock(com.operativus.agentmanager.compute.security.FormatPreservingEncryptionService.class),
                mock(com.operativus.agentmanager.compute.security.PiiAuditLogRepository.class),
                new SimpleMeterRegistry());
        assertEquals(PII_BOUNDARY_ORDER, advisor.getOrder(),
                "PIIAnonymizationAdvisor.getOrder() defines the boundary; downstream "
                        + "advisors and this test depend on it being 10. If you change it, "
                        + "update PII_BOUNDARY_ORDER and audit every other advisor's position.");
    }

    /**
     * Advisors that read raw prompt content via the chain — must run AT OR AFTER the
     * boundary so they observe the redacted form. Audit findings F11 (VectorStoreCacheAdvisor)
     * and F12 (ExtensionHookAdvisor) regressed exactly this property and shipped advisors
     * at order 5 and -10 respectively. Each row below pins the post-fix value.
     */
    @Test
    void contentSafetyAdvisor_runsAfterPiiBoundary() {
        ContentSafetyAdvisor advisor = new ContentSafetyAdvisor(
                mock(ModerationService.class), new SimpleMeterRegistry());
        assertTrue(advisor.getOrder() >= PII_BOUNDARY_ORDER,
                "ContentSafetyAdvisor moderates the LLM output (sees prompt context too) — "
                        + "must run AFTER PII redactor; got " + advisor.getOrder());
    }

    @Test
    void vectorStoreCacheAdvisor_runsAfterPiiBoundary_F11() {
        VectorStoreCacheAdvisor advisor = new VectorStoreCacheAdvisor(
                mock(org.springframework.ai.vectorstore.VectorStore.class),
                new SimpleMeterRegistry(),
                mock(com.operativus.agentmanager.control.finops.service.LiveValuationEngine.class),
                mock(ModerationService.class));
        assertTrue(advisor.getOrder() >= PII_BOUNDARY_ORDER,
                "VectorStoreCacheAdvisor caches prompts as embedding+content rows — "
                        + "must run AFTER PII redactor or raw user content reaches the "
                        + "embedding API and the cache table (audit F11); got " + advisor.getOrder());
    }

    @Test
    void extensionHookAdvisor_runsAfterPiiBoundary_F12() {
        ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                Collections.emptyList(), Collections.emptyList(),
                mock(com.operativus.agentmanager.control.repository.ExtensionRegistrationRepository.class),
                mock(org.springframework.web.reactive.function.client.WebClient.class),
                com.operativus.agentmanager.core.spi.OutputPiiScrubber.NO_OP,
                new SimpleMeterRegistry());
        assertTrue(advisor.getOrder() >= PII_BOUNDARY_ORDER,
                "ExtensionHookAdvisor POSTs prompt content to third-party webhook URLs — "
                        + "must run AFTER PII redactor or raw user content is exfiltrated to "
                        + "admin-configured external endpoints (audit F12); got " + advisor.getOrder());
    }

    /**
     * Advisors that read prompt content BEFORE the PII boundary — kept here so the
     * decision is auditable. Each must be a defensive consumer that does NOT leak
     * the matched text via log statements, exception messages, network calls, or
     * persistent writes.
     *
     * <p>{@link PromptInjectionAdvisor} (order 0): regex-matches the prompt; on match,
     * throws {@code SecurityException} with a redacted message (audit F3 fix removed
     * the prompt content from the exception message specifically because of this
     * pre-boundary position). No log statement carries the matched text.</p>
     *
     * <p>{@link PromptInjectionScanner} (order -100): regex-matches against
     * {@code request.prompt().getContents()} (audit F10 fix; previously
     * {@code request.toString()}); on match, throws {@code BusinessValidationException}
     * with a generic message. The advisor logs only the matched signature, not the
     * input text. No network call.</p>
     */
    @Test
    void promptInjectionAdvisor_runsBeforeBoundary_butIsNonLeaking() {
        PromptInjectionAdvisor advisor = new PromptInjectionAdvisor(new SimpleMeterRegistry());
        assertTrue(advisor.getOrder() < PII_BOUNDARY_ORDER,
                "PromptInjectionAdvisor is documented as a pre-boundary defensive check; "
                        + "if its order changes, re-audit whether the early read of prompt content "
                        + "still cannot leak via exception/log; got " + advisor.getOrder());
    }

    @Test
    void promptInjectionScanner_runsBeforeBoundary_butIsNonLeaking() {
        PromptInjectionScanner scanner = new PromptInjectionScanner();
        assertTrue(scanner.getOrder() < PII_BOUNDARY_ORDER,
                "PromptInjectionScanner is documented as a pre-boundary defensive check; "
                        + "if its order changes, re-audit whether the early read of prompt content "
                        + "still cannot leak via exception/log; got " + scanner.getOrder());
    }

    /**
     * K6 — AdvancedRagAdvisor (order 0) MUST run BEFORE the PII boundary so that
     * retrieved KB chunks injected into the prompt are subject to PII redaction at
     * order 10. If the RAG advisor moved AFTER PII, chunks containing PII (e.g.,
     * ingested documents with embedded SSNs) would reach the LLM and downstream
     * advisors (logging at 20, content safety at 20) unredacted.
     *
     * <p><strong>Known leak-risk tradeoff documented here.</strong>
     * Because RAG runs pre-boundary, the user query passed to {@code knowledgeService.search}
     * is the RAW (unredacted) prompt — so the embedding API call carries raw user content
     * including any PII the user typed. This is the same F11-class concern that pushed
     * {@code VectorStoreCacheAdvisor} after the boundary. The tradeoff here is asymmetric:
     * <ul>
     *   <li>Move BEFORE boundary (current): chunk-injection redaction works, but
     *       embedding API sees raw user PII.</li>
     *   <li>Move AFTER boundary (hypothetical): embedding API sees redacted query, but
     *       chunk injection happens AFTER PII redaction → injected PII reaches the LLM.</li>
     * </ul>
     * Neither pure position is correct; a future fix would split RAG into two phases
     * (pre-boundary search with caller-controlled redaction + post-boundary injection).
     * Until then, this test pins the current decision and flags the embedding leak as
     * known.</p>
     */
    @Test
    void advancedRagAdvisor_runsBeforeBoundary_chunkInjectionSubjectToRedaction() {
        AdvancedRagAdvisor advisor = new AdvancedRagAdvisor(
                mock(com.operativus.agentmanager.control.service.KnowledgeService.class),
                org.springframework.ai.vectorstore.SearchRequest.builder().query("probe").build(),
                mock(DocumentReRanker.class),
                new SimpleMeterRegistry());
        assertTrue(advisor.getOrder() < PII_BOUNDARY_ORDER,
                "AdvancedRagAdvisor MUST run BEFORE the PII boundary so injected KB chunks "
                        + "are subject to PII redaction at order 10. Moving it AFTER 10 would "
                        + "let RAG-injected PII reach the LLM unredacted. Got order "
                        + advisor.getOrder() + ".");
    }

    /**
     * M3 — Memory advisor (AgenticMemoryAdvisor at order 50) runs AFTER the PII boundary.
     * This is DIFFERENT from
     * {@link AdvancedRagAdvisor} (order 0, pre-PII) and is a DELIBERATE product choice:
     *
     * <p>The memory advisor INJECTS content into the prompt, similar to RAG. Unlike RAG —
     * where injected KB chunks may contain arbitrary user-ingested content — memory
     * content is conceptually "curated agent context" (user preferences distilled by
     * the extractor). The design choice is "don't redact intentional context" because
     * redacting it would defeat the purpose of agentic memory.
     *
     * <p><strong>Known leak risk documented here</strong>: if a memory was ingested with
     * user-supplied PII (e.g., the user typed "my SSN is X, please remember it"), that
     * PII flows into the LLM context unredacted because:
     * <ul>
     *   <li>The PII redactor at order 10 has already run on the user input.</li>
     *   <li>The memory advisor at order 49/50 INJECTS retrieved content AFTER redaction.</li>
     *   <li>The injected content is not re-scanned for PII before the LLM call.</li>
     * </ul>
     *
     * <p>Pinning the orders here prevents an accidental move (a future "moved memory
     * advisor to pre-PII for safety" change would flip these assertions). The risk is
     * acknowledged but not fixed; a full fix requires either (a) sanitize memories at
     * ingestion time so they never contain PII to begin with, or (b) split each memory
     * advisor into pre-boundary search + post-boundary injection like the RAG-advisor
     * fix path documented in {@link #advancedRagAdvisor_runsBeforeBoundary_chunkInjectionSubjectToRedaction}.
     */
    @Test
    void agenticMemoryAdvisor_runsAfterBoundary_injectsAfterRedaction() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(
                mock(com.operativus.agentmanager.core.registry.MemoryOperations.class),
                "test-user");
        assertTrue(advisor.getOrder() >= PII_BOUNDARY_ORDER,
                "AgenticMemoryAdvisor injects user-specific memory AFTER PII redaction by "
                        + "design — memory content is curated context, not subject to user-"
                        + "input redaction. Moving this BEFORE PII would still leak (injected "
                        + "memories would reach the LLM unsanitized by the redactor on a "
                        + "different path). Got order " + advisor.getOrder() + ".");
        assertEquals(50, advisor.getOrder(),
                "AgenticMemoryAdvisor order pinned at 50 — a deliberate slot AFTER PII "
                        + "(boundary at 10) and content-safety (20), BEFORE the RAG external "
                        + "search path that orchestrates retrieval. See class Javadoc for the "
                        + "leak-risk tradeoff (memories ingested with user PII reach LLM unredacted).");
    }

    // CulturalMemoryAdvisor (was order 49, post-PII) was dropped pre-launch — see
    // docs/analysis/agm-advisor-chain-audit.md. Slot 49 is unused; the boundary
    // pin for AgenticMemoryAdvisor at order 50 above is sufficient for the
    // "memory advisors run AFTER PII redaction" contract.

    /**
     * Helpful documentation of where to add new advisors. Lists pin known orders so
     * `git diff` on this test surfaces any reorder. A reviewer reading the diff sees
     * the relative-position invariant directly.
     */
    @Test
    void knownAdvisorOrderingSnapshot_documentsTheChain() {
        assertEquals(-100, new PromptInjectionScanner().getOrder(),
                "PromptInjectionScanner: -100 (defensive)");
        assertEquals(0, new PromptInjectionAdvisor(new SimpleMeterRegistry()).getOrder(),
                "PromptInjectionAdvisor: 0 (defensive)");
        assertEquals(0, new AdvancedRagAdvisor(
                mock(com.operativus.agentmanager.control.service.KnowledgeService.class),
                org.springframework.ai.vectorstore.SearchRequest.builder().query("probe").build(),
                mock(DocumentReRanker.class),
                new SimpleMeterRegistry()).getOrder(),
                "AdvancedRagAdvisor: 0 (pre-PII so injected chunks are redacted; K6 documents "
                        + "the embedding-API-leak tradeoff)");
        // PIIAnonymizationAdvisor: 10 (boundary, asserted in piiAnonymizationAdvisor_definesTheBoundary).
        assertEquals(15, new ExtensionHookAdvisor(
                Collections.emptyList(), Collections.emptyList(),
                mock(com.operativus.agentmanager.control.repository.ExtensionRegistrationRepository.class),
                mock(org.springframework.web.reactive.function.client.WebClient.class),
                com.operativus.agentmanager.core.spi.OutputPiiScrubber.NO_OP,
                new SimpleMeterRegistry()).getOrder(),
                "ExtensionHookAdvisor: 15 (post-PII per F12)");
        assertEquals(15, new VectorStoreCacheAdvisor(
                mock(org.springframework.ai.vectorstore.VectorStore.class),
                new SimpleMeterRegistry(),
                mock(com.operativus.agentmanager.control.finops.service.LiveValuationEngine.class),
                mock(ModerationService.class)).getOrder(),
                "VectorStoreCacheAdvisor: 15 (post-PII per F11)");
        assertEquals(20, new ContentSafetyAdvisor(
                mock(ModerationService.class), new SimpleMeterRegistry()).getOrder(),
                "ContentSafetyAdvisor: 20 (post-PII)");
        // 49 — formerly CulturalMemoryAdvisor; advisor removed pre-launch (see
        //     docs/analysis/agm-advisor-chain-audit.md). Slot unused.
        assertEquals(50, new AgenticMemoryAdvisor(
                mock(com.operativus.agentmanager.core.registry.MemoryOperations.class),
                "test-user").getOrder(),
                "AgenticMemoryAdvisor: 50 (post-PII; M3 documents the leak-risk tradeoff "
                        + "for user-PII-bearing memories)");
    }
}
