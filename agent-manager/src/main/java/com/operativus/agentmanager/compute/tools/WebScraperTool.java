package com.operativus.agentmanager.compute.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.compute.service.WebsiteCrawlerService;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.control.service.queue.WebsiteCrawlJobHandler;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Lazy;

import com.operativus.agentmanager.core.registry.KnowledgeOperations;

/**
 * Domain Responsibility: Provides Spring AI tools for extensive web scraping using Firecrawl API integration. 
 * State: Stateless
 */
@AgentToolComponent
public class WebScraperTool {

    private static final Logger log = LoggerFactory.getLogger(WebScraperTool.class);
    private final KnowledgeOperations knowledgeOperations;
    private final WebsiteCrawlerService websiteCrawlerService;
    private final PersistentJobQueueService jobQueueService;
    private final ObjectMapper objectMapper;

    // @Lazy breaks a bean cycle: this tool is pulled into globalToolProvider, which AgentClientFactory
    // needs to build chat clients; PersistentJobQueueService's handlers transitively depend on
    // AgentService → AgentClientFactory. A lazy proxy defers queue resolution past construction.
    public WebScraperTool(KnowledgeOperations knowledgeOperations, WebsiteCrawlerService websiteCrawlerService,
                          @Lazy PersistentJobQueueService jobQueueService, ObjectMapper objectMapper) {
        this.knowledgeOperations = knowledgeOperations;
        this.websiteCrawlerService = websiteCrawlerService;
        this.jobQueueService = jobQueueService;
        this.objectMapper = objectMapper;
    }

    /**
     * @summary Synchronously rips targeted URLs using the isolated Firecrawl microservice and protects LLM token contexts.
     * @logic Delegates to the WebsiteCrawlerService for pure Markdown extraction and truncates payloads exceeding 50,000 chars.
     * @sideEffects Blocks the calling Agent LLM thread until response is returned. Time is bound by REST client timeouts.
     */
    @RequiresCapability("web_access")
    @Tool(description = "Reads a specific webpage URL and extracts its main text content. Pass the absolute URL. Use this to read documentation before saving to knowledge base.")
    public String readWebpage(String url) {
        log.info("Agent is reading webpage using Firecrawl: {}", url);
        long startTime = System.currentTimeMillis();
        
        try {
            String content = websiteCrawlerService.scrapeWebpage(url);

            if (content == null || content.isEmpty()) {
                return "No content found.";
            }

            // GUARANTEE INGESTION: Auto-save the scraped content so it appears in the UI instantly,
            // preventing the LLM from 'forgetting' to execute pushToKnowledgeBase.
            try {
                java.util.UUID kbId = knowledgeOperations.resolveCategoryId("Single Scrapes", url);
                knowledgeOperations.ingestText("Single Scrape: " + url, content, url, kbId);
                log.info("Auto-ingested single page {} into Knowledge Base directly.", url);
            } catch (Exception e) {
                log.warn("Failed to auto-ingest single page {}: {}", url, e.getMessage());
            }

            if (content.length() > 50000) {
                log.debug("Content from {} exceeded 50,000 chars. Truncating to protect LLM context.", url);
                // Truncate to avoid blowing up the LLM context window before summarization
                return content.substring(0, 50000) + "... [Content Truncated]";
            }
            log.debug("Successfully read {} characters of Markdown from {}", content.length(), url);
            return content;
            
        } catch (Exception e) {
            log.error("Failed to read webpage with Firecrawl: {}", url, e);
            return "Failed to fetch webpage content: " + e.getMessage();
        } finally {
            log.info("Finished reading webpage {} in {}ms", url, (System.currentTimeMillis() - startTime));
        }
    }

    /**
     * @summary Facilitates the permanent DB storage of summarized documents into the relevant Vector clusters.
     * @logic Maps Category Name constraints into definitive PGVector Collection IDs via the Database Repository.
     * @sideEffects Writes into the Vector database using the KnowledgeService.
     */
    @RequiresCapability("web_access")
    @Tool(description = "Pushes the final, summarized, clean Markdown documentation to the PGVector Knowledge Base for permanent storage. Requires a title, the markdown content, the sourceUrl, and a categoryName. If the user hasn't specified a specific collection, securely invent a descriptive name based on the topic. Never pass an empty string.")
    public String pushToKnowledgeBase(String title, String markdownContent, String sourceUrl, String categoryName) {
        try {
            log.info("Agent is pushing content to Knowledge Base: {} under category: {}", title, categoryName);
            log.debug("Pushing payload size: {} characters to category: {}", markdownContent.length(), categoryName);
            java.util.UUID kbId = knowledgeOperations.resolveCategoryId(categoryName, sourceUrl);
            knowledgeOperations.ingestText(title, markdownContent, sourceUrl, kbId);
            return "Successfully ingested documentation into the PGVector Knowledge Base.";
        } catch (Exception e) {
            log.error("Failed to push to knowledge base", e);
            return "Failed to ingress content to Knowledge Base: " + e.getMessage();
        }
    }

    /**
     * @summary Kicks off the non-blocking Firecrawl background process for deep crawling domain paths.
     * @logic Maps Category constraints and triggers the async Virtual Thread loop in the WebsiteCrawlerService.
     * @sideEffects Unblocks the LLM immediately and returns a tracking confirmation string.
     */
    @RequiresCapability("web_access")
    @Tool(description = "Asynchronously crawls a documentation website starting from a base URL, extracting content from all child pages and ingesting them into the Knowledge Base. Runs in the background. Use this when asked to scrape an entire website or docs site. You MUST provide a categoryName. If the user doesn't specify one, invent a short, descriptive collection name based on the documentation topic (e.g. 'Spring Boot Docs', 'Procurator'). Never pass an empty string.")
    public String bulkIngestDocumentationSite(String baseUrl, String categoryName) {
        log.info("Agent requested bulk crawl of {} under category: {}", baseUrl, categoryName);

        // Preflight: fail fast with an actionable message if the crawler backend is down, instead of
        // enqueuing a job that would immediately fail in the background.
        if (!websiteCrawlerService.isFirecrawlHealthy()) {
            log.warn("Bulk crawl rejected — Firecrawl service unavailable (baseUrl={})", baseUrl);
            return "The web crawling service is currently unavailable, so the crawl could not be started. "
                    + "Tell the user the scraping backend is offline and to try again later.";
        }

        try {
            // Capture tenant context now (we're on the agent's bound thread); the queue worker
            // re-binds it from the payload — see WebsiteCrawlJobHandler.
            String payload = objectMapper.writeValueAsString(new WebsiteCrawlJobHandler.Payload(
                    baseUrl, categoryName, AgentContextHolder.getOrgId(), AgentContextHolder.getUserId()));
            // jobKey dedups concurrent crawls of the same site.
            var job = jobQueueService.enqueue(
                    WebsiteCrawlJobHandler.JOB_TYPE, null, payload, null, "CRAWL_" + baseUrl);
            log.info("Enqueued WEBSITE_CRAWL job {} for {}", job.getId(), baseUrl);
            return "Started a tracked background crawl of " + baseUrl + " (job " + job.getId() + "). "
                    + "Pages will appear in the Knowledge Base"
                    + (categoryName != null ? " under '" + categoryName + "'" : "")
                    + " as the crawl progresses. The agent does not need to wait for it to finish.";
        } catch (Exception e) {
            log.error("Failed to enqueue bulk crawl for {}", baseUrl, e);
            return "Failed to start the crawl: " + e.getMessage();
        }
    }

}
