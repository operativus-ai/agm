package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.compute.service.WebsiteCrawlerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Runtime test for {@link FirecrawlSearchTool}. Asserts the
 * 5 vectors per docs/plans/agm-tools-impl.md §3:
 *   (a) firecrawl_web_search happy path returns SearchResult wrapping crawler output
 *   (b) firecrawl_scrape_url happy path returns SearchResult wrapping crawler output
 *   (c) blank query short-circuits — crawler service NEVER called, returns canonical error
 *   (d) firecrawl_web_search exception path returns "Failed to perform native web search:"
 *   (e) firecrawl_scrape_url exception path returns "Failed to perform native web scrape:"
 *
 * State: Stateless. Mockito stub of WebsiteCrawlerService is independent ground truth (A18).
 */
class FirecrawlSearchToolTest {

    private final WebsiteCrawlerService crawler = mock(WebsiteCrawlerService.class);
    private final FirecrawlSearchTool tool = new FirecrawlSearchTool(crawler);

    // (a) web_search happy path
    @Test
    void webSearch_happyPath_returnsCrawlerOutput() {
        when(crawler.searchWeb("apple news")).thenReturn("news content");

        FirecrawlSearchTool.SearchResult result = tool.firecrawlWebSearch("apple news");

        assertEquals("news content", result.content());
    }

    // (b) scrape_url happy path
    @Test
    void scrapeUrl_happyPath_returnsCrawlerOutput() {
        when(crawler.scrapeWebpage("https://cnn.com")).thenReturn("page markdown");

        FirecrawlSearchTool.SearchResult result = tool.firecrawlScrapeUrl("https://cnn.com");

        assertEquals("page markdown", result.content());
    }

    // (c) blank query short-circuit — crawler MUST NOT be called
    @Test
    void blankQuery_shortCircuitsBeforeCrawler() {
        FirecrawlSearchTool.SearchResult result = tool.firecrawlWebSearch("");

        assertEquals("Error: Empty search query provided.", result.content());
        verify(crawler, never()).searchWeb(org.mockito.ArgumentMatchers.anyString());
    }

    // (d) web_search exception -> structured failure
    @Test
    void webSearch_exception_returnsFailedNativeWebSearch() {
        when(crawler.searchWeb("query")).thenThrow(new RuntimeException("api down"));

        FirecrawlSearchTool.SearchResult result = tool.firecrawlWebSearch("query");

        assertTrue(result.content().startsWith("Failed to perform native web search:"),
                "expected web-search failure prefix, got: " + result.content());
        assertTrue(result.content().contains("api down"), "expected cause echoed");
    }

    // (e) scrape_url exception -> structured failure
    @Test
    void scrapeUrl_exception_returnsFailedNativeWebScrape() {
        when(crawler.scrapeWebpage("https://x.com")).thenThrow(new RuntimeException("scrape fail"));

        FirecrawlSearchTool.SearchResult result = tool.firecrawlScrapeUrl("https://x.com");

        assertTrue(result.content().startsWith("Failed to perform native web scrape:"),
                "expected scrape failure prefix, got: " + result.content());
        assertTrue(result.content().contains("scrape fail"), "expected cause echoed");
    }
}
