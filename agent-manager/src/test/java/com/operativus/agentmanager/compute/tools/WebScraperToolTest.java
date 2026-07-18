package com.operativus.agentmanager.compute.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.compute.service.WebsiteCrawlerService;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.core.registry.KnowledgeOperations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Runtime test for {@link WebScraperTool#readWebpage(String)} — the
 * Agno-equivalent surface (read a webpage as text). Asserts the 4 vectors per
 * docs/plans/agm-tools-impl.md §3:
 *   (a) under 50k chars returns crawler output untruncated
 *   (b) > 50k chars truncated and ends with "... [Content Truncated]"
 *   (c) crawler exception returns "Failed to fetch webpage content:"
 *   (d) empty crawler output returns "No content found."
 *
 * State: Stateless. Out of scope: pushToKnowledgeBase / bulkIngestDocumentationSite —
 * AGM-specific KB ops, not Agno parity. Their HITL tier is verified by
 * HitlAdvisorNativeToolWiringTest.
 */
class WebScraperToolTest {

    private final WebsiteCrawlerService crawler = mock(WebsiteCrawlerService.class);
    private final KnowledgeOperations knowledge = mock(KnowledgeOperations.class);
    private final PersistentJobQueueService jobQueueService = mock(PersistentJobQueueService.class);
    private final WebScraperTool tool = new WebScraperTool(knowledge, crawler, jobQueueService, new ObjectMapper());

    private static final String TRUNCATION_MARKER = "... [Content Truncated]";

    // (a) under 50k chars — passes through unchanged. Note: production also auto-ingests
    // into the knowledge base ("GUARANTEE INGESTION" comment in source) — that side effect
    // is intentional and out of scope for the Agno-parity surface (read a webpage). With
    // the KnowledgeOperations mock returning null UUID by default, the inner try/catch
    // swallows the resulting NPE-on-null and the method returns the crawler content.
    @Test
    void readWebpage_under50k_returnsContentUnchanged() {
        String content = "x".repeat(30_000);
        when(crawler.scrapeWebpage("https://example.com")).thenReturn(content);

        String result = tool.readWebpage("https://example.com");

        assertEquals(content, result);
    }

    // (b) > 50k chars — truncates with marker
    @Test
    void readWebpage_over50k_truncatesAndAppendsMarker() {
        String content = "y".repeat(60_000);
        when(crawler.scrapeWebpage("https://big.com")).thenReturn(content);

        String result = tool.readWebpage("https://big.com");

        assertTrue(result.endsWith(TRUNCATION_MARKER),
                "expected truncation marker suffix, got tail: " + result.substring(result.length() - 40));
        assertEquals(50_000 + TRUNCATION_MARKER.length(), result.length(),
                "expected exactly 50k content + marker length");
    }

    // (c) crawler exception -> structured failure
    @Test
    void readWebpage_exception_returnsFailedFetch() {
        when(crawler.scrapeWebpage("https://broken.com")).thenThrow(new RuntimeException("connection refused"));

        String result = tool.readWebpage("https://broken.com");

        assertTrue(result.startsWith("Failed to fetch webpage content:"),
                "expected failure prefix, got: " + result);
        assertTrue(result.contains("connection refused"), "expected cause echoed");
    }

    // (d) empty content -> canonical fallback
    @Test
    void readWebpage_emptyContent_returnsNoContentFound() {
        when(crawler.scrapeWebpage("https://blank.com")).thenReturn("");

        String result = tool.readWebpage("https://blank.com");

        assertEquals("No content found.", result);
    }
}
