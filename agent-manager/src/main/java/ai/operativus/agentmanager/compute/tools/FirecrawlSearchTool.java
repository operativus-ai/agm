package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.compute.service.WebsiteCrawlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;


/**
 * Domain Responsibility: Provides Spring AI integration for dynamic internet searches using the Firecrawl backend API natively.
 * State: Stateless
 */
@AgentToolComponent
public class FirecrawlSearchTool {

    private static final Logger log = LoggerFactory.getLogger(FirecrawlSearchTool.class);
    private final WebsiteCrawlerService websiteCrawlerService;

    public FirecrawlSearchTool(WebsiteCrawlerService websiteCrawlerService) {
        this.websiteCrawlerService = websiteCrawlerService;
    }

    public record SearchResult(String content) {}

    /**
     * @summary Conducts internet queries through the native Firecrawl search API to bring live context into LLM sessions.
     * @logic Delegates straight to the WebsiteCrawlerService cleanly avoiding all secondary NodeJS/MCP wrappers.
     * @sideEffects Synchronous thread blocking constraint limited by HTTP timeout thresholds inside the RestClient.
     */
    @RequiresCapability("web_access")
    @Tool(name = "firecrawl_web_search", description = "Search the real-time public internet and live news websites using the Firecrawl search engine. You HAVE PERMISSION and MUST use this tool to fetch current events, news, or live data instead of refusing. Always provide a clear, concise search query string (like 'current news about Apple').")
    public SearchResult firecrawlWebSearch(String query) {
        try {
            log.info("Agent executing native search query: {}", query);
            long startTime = System.currentTimeMillis();
            
            if (query == null || query.isBlank()) {
                return new SearchResult("Error: Empty search query provided.");
            }

            String results = websiteCrawlerService.searchWeb(query);
            
            log.info("Finished firecrawl native search {} in {}ms", query, (System.currentTimeMillis() - startTime));
            return new SearchResult(results);
        } catch (Exception e) {
            log.error("Firecrawl web search failed", e);
            return new SearchResult("Failed to perform native web search: " + e.getMessage());
        }
    }

    /**
     * @summary Scrapes a specific website URL using the Firecrawl backend.
     */
    @RequiresCapability("web_access")
    @Tool(name = "firecrawl_scrape_url", description = "Use this tool to read the contents of ANY arbitrary webpage the user specifies. You HAVE PERMISSION to browse the internet to process URLs. It is MANDATORY to use this tool when asked for information or news from a specific URL like 'https://cnn.com'.")
    public SearchResult firecrawlScrapeUrl(String url) {
        try {
            log.info("Agent executing native URL scrape: {}", url);
            long startTime = System.currentTimeMillis();
            
            if (url == null || url.isBlank()) {
                return new SearchResult("Error: Empty URL provided.");
            }

            String results = websiteCrawlerService.scrapeWebpage(url);
            
            log.info("Finished firecrawl native scrape {} in {}ms", url, (System.currentTimeMillis() - startTime));
            return new SearchResult(results);
        } catch (Exception e) {
            log.error("Firecrawl web scrape failed", e);
            return new SearchResult("Failed to perform native web scrape: " + e.getMessage());
        }
    }
}
