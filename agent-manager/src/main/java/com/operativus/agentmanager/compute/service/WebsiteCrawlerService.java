package com.operativus.agentmanager.compute.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.operativus.agentmanager.core.registry.KnowledgeOperations;
import com.operativus.agentmanager.core.registry.ConfigurationProvider;
import com.operativus.agentmanager.core.security.SsrfGuard;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Domain Responsibility: Orchestrates distributed, asynchronous web crawling and semantic markdown extraction targeting specific base domains. Integrates with self-hosted Firecrawl API for intelligent RAG scraping.
 * State: Stateless
 */
@Service
public class WebsiteCrawlerService {

    private static final Logger log = LoggerFactory.getLogger(WebsiteCrawlerService.class);

    /** Stop polling a crawl after this many consecutive status-call failures (transient 5xx /
     *  network blips). Prevents a permanently-broken Firecrawl from being polled forever. */
    private static final int MAX_CONSECUTIVE_POLL_ERRORS = 3;
    /** Hard cap on how many {@code next} cursor pages we follow within a single poll — guards
     *  against a pathological Firecrawl response that returns a self-referential cursor. */
    private static final int MAX_NEXT_PAGE_FOLLOWS = 500;

    private final KnowledgeOperations knowledgeOperations;
    private final ConfigurationProvider settingsService;
    private final RestClient restClient;
    private final long crawlPollIntervalMs;
    private final long crawlTimeoutMs;
    /** When false (prod default), agent-supplied scrape/crawl URLs targeting loopback/RFC-1918/
     *  cloud-metadata are refused. Firecrawl shares AGM's internal network, so an unguarded URL
     *  would let an agent make Firecrawl fetch internal services (SSRF). Mirrors the ingest guard. */
    private final boolean scrapeAllowLoopback;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public WebsiteCrawlerService(
            KnowledgeOperations knowledgeOperations,
            ConfigurationProvider settingsService,
            @Value("${app.firecrawl.baseUrl:http://localhost:3002}") String firecrawlBaseUrl,
            @Value("${app.firecrawl.crawl.poll-interval-ms:5000}") long crawlPollIntervalMs,
            @Value("${app.firecrawl.crawl.timeout-seconds:900}") long crawlTimeoutSeconds,
            @Value("${app.firecrawl.scrape.allow-loopback-urls:false}") boolean scrapeAllowLoopback,
            RestClient.Builder restClientBuilder) {
        this.knowledgeOperations = knowledgeOperations;
        this.settingsService = settingsService;
        this.crawlPollIntervalMs = crawlPollIntervalMs;
        this.crawlTimeoutMs = crawlTimeoutSeconds * 1000L;
        this.scrapeAllowLoopback = scrapeAllowLoopback;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);  // 5 second connect timeout
        factory.setReadTimeout(60000); // 60 second read timeout for complex crawls

        this.restClient = restClientBuilder
                .baseUrl(firecrawlBaseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * @summary Cheap liveness probe for the Firecrawl service, used by tools to fail fast with a
     *     clear message instead of letting a connection-refused error surface as a confusing
     *     mid-task failure.
     * @logic GETs the Firecrawl root with the configured short connect timeout. Any 2xx/3xx/4xx
     *     response means the service is reachable; only a transport failure (refused/timeout)
     *     counts as down.
     */
    public boolean isFirecrawlHealthy() {
        try {
            restClient.get().uri("/").retrieve().toBodilessEntity();
            return true;
        } catch (ResourceAccessException e) {
            log.warn("Firecrawl health check failed (service unreachable): {}", e.getMessage());
            return false;
        } catch (Exception e) {
            // Any HTTP-level response (even 404) means the service is up and answering.
            return true;
        }
    }

    public record ScrapeRequest(String url, List<String> formats) {}
    public record ScrapeResponse(boolean success, ScrapeData data) {}
    public record ScrapeData(String markdown, Map<String, Object> metadata) {}

    public record CrawlOptions(List<String> formats) {}
    public record CrawlRequest(String url, int limit, CrawlOptions scrapeOptions) {}
    public record CrawlInitResponse(boolean success, String id) {}

    // Firecrawl's /v1/crawl/{id} response paginates: large crawls return a `next` cursor URL
    // alongside the current page of `data`. Following it is REQUIRED or big crawls silently
    // ingest only the first page. `total`/`completed` drive progress reporting.
    public record CrawlStatusResponse(boolean success, String status, Integer total, Integer completed,
                                      String next, List<ScrapeData> data) {}

    public record SearchRequest(String query, Integer limit) {}
    public record SearchResponse(boolean success, List<SearchData> data) {}
    public record SearchData(String url, String title, String description) {}

    /**
     * @summary Synchronously scrapes a single webpage using the Firecrawl API and returns the extracted markdown content.
     * @logic
     * Executes a blocking POST request to the Firecrawl /v1/scrape endpoint and parses the response to extract the markdown payload.
     */
    public String scrapeWebpage(String url) {
        String ssrfError = SsrfGuard.validate(url, scrapeAllowLoopback);
        if (ssrfError != null) {
            log.warn("Refusing scrape of URL '{}': {}", url, ssrfError);
            return "Refused to scrape URL: " + ssrfError;
        }
        try {
            log.info("Requesting Firecrawl scrape for URL: {}", url);
            ScrapeRequest requestPayload = new ScrapeRequest(url, List.of("markdown"));
            log.debug("Firecrawl Request Payload [/v1/scrape]: {}", requestPayload);

            ScrapeResponse response = restClient.post()
                    .uri("/v1/scrape")
                    .body(requestPayload)
                    .retrieve()
                    .body(ScrapeResponse.class);
            log.debug("Firecrawl Response Status [/v1/scrape]: {}", response);

            if (response != null && response.success() && response.data() != null) {
                return response.data().markdown();
            }
            return "Failed to scrape content. Empty response from crawler.";
        } catch (ResourceAccessException e) {
            // Connection refused / timeout — the Firecrawl service itself is unreachable, not a
            // page-level failure. Surface an actionable message instead of a raw I/O exception.
            log.error("Firecrawl unreachable while scraping {}: {}", url, e.getMessage());
            return "The web scraping service is currently unavailable. Please tell the user the "
                    + "scraping backend is offline and the request cannot be completed right now.";
        } catch (Exception e) {
            log.error("Failed to scrape URL {}: {}", url, e.getMessage());
            return "Failed to fetch webpage content: " + e.getMessage();
        }
    }

    /**
     * @summary Queries the web dynamically using the Firecrawl search API.
     * @logic
     * Executes a POST to /v1/search retrieving exactly 5 highly-relevant markdown/snippet documents. Assembles these documents into a compressed LLM-friendly context payload, bounding the maximum size to 20,000 characters to protect the LLM token window.
     */
    public String searchWeb(String searchQuery) {
        try {
            log.info("Requesting Firecrawl search for query: '{}'", searchQuery);
            // Defaulting to 4 high-quality results to prevent token saturation while maximizing insight retrieval.
            SearchRequest requestPayload = new SearchRequest(searchQuery, 4);
            log.debug("Firecrawl Request Payload [/v1/search]: {}", requestPayload);

            SearchResponse response = restClient.post()
                    .uri("/v1/search")
                    .body(requestPayload)
                    .retrieve()
                    .body(SearchResponse.class);

            if (response != null && response.success() && response.data() != null && !response.data().isEmpty()) {
                StringBuilder packedResults = new StringBuilder();
                packedResults.append("# Web Search Results for: '").append(searchQuery).append("'\n\n");
                
                for (SearchData data : response.data()) {
                    packedResults.append("## [").append(data.title() != null ? data.title() : "Untitled").append("]\n");
                    packedResults.append("**URL**: ").append(data.url()).append("\n");
                    if (data.description() != null && !data.description().isBlank()) {
                        packedResults.append("**Context**: ").append(data.description()).append("\n");
                    }
                    packedResults.append("---\n");
                }
                
                String finalContext = packedResults.toString();
                // Safety bound context size to 20,000 chars roughly.
                if (finalContext.length() > 20000) {
                    finalContext = finalContext.substring(0, 20000) + "\n... [Remaining results truncated to protect LLM context window]";
                }
                return finalContext;
            }
            return "No comprehensive search results found for query: " + searchQuery;
        } catch (Exception e) {
            log.error("Failed to execute Firecrawl search {}: {}", searchQuery, e.getMessage());
            return "Failed to perform web search using Firecrawl: " + e.getMessage();
        }
    }

    /**
     * @summary Initiates an asynchronous background job to recursively crawl and ingest a website into the generic knowledge base.
     * @logic
     * Delegates to the overloaded method passing a null categoryName to signify the default knowledge base path.
     */
    public void crawlAndIngestSiteAsync(String baseUrl) {
        crawlAndIngestSiteAsync(baseUrl, null);
    }
    
    /**
     * @summary Initiates an asynchronous background job to recursively crawl and ingest a website targeted at a specific KnowledgeBase.
     * @logic
     * Logs the start of the background job and spawns a new Virtual Thread to execute the performBulkIngestion logic, guaranteeing non-blocking behavior for the main thread.
     */
    public void crawlAndIngestSiteAsync(String baseUrl, String categoryName) {
        log.info("Started background bulk ingestion job for {}, category: {}", baseUrl, categoryName);
        // F20 — fresh VT does NOT inherit JDK 21 ScopedValues. Capture caller's AgentContextHolder
        // bindings so knowledgeOperations.resolveCategoryId / ingestText inside crawlSiteBlocking
        // see the correct orgId for tenant-scoped vector store writes.
        // NOTE: the agent tool path now enqueues a tracked WEBSITE_CRAWL job instead — prefer that.
        // This fire-and-forget wrapper is retained for direct/non-agent callers and swallows failures.
        final com.operativus.agentmanager.core.callback.AgentContextSnapshot snapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        virtualThreadExecutor.submit(() -> snapshot.run(() -> {
            try {
                crawlSiteBlocking(baseUrl, categoryName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Background bulk crawl interrupted for {}", baseUrl);
            } catch (Exception e) {
                log.error("Background bulk crawl failed for {}: {}", baseUrl, e.getMessage());
            }
        }));
    }

    /**
     * @summary Synchronously crawls a site via Firecrawl and ingests every page into the Knowledge
     *     Base, returning the number of pages ingested. Driven by {@code WebsiteCrawlJobHandler} so
     *     the work is tracked in the job table, retryable, and survives a restart.
     * @logic POST /v1/crawl to start, then poll GET /v1/crawl/{id} every {@code crawlPollIntervalMs}
     *     until a terminal state — bounded by {@code crawlTimeoutMs} (wall-clock) and a consecutive
     *     poll-error cap. Each poll drains ALL available result pages by following Firecrawl's
     *     {@code next} pagination cursor (without it, large crawls silently ingest only page one).
     * @throws IllegalStateException if the crawl can't be initialised or ends failed/cancelled, or
     *     if polling fails repeatedly.
     * @throws java.util.concurrent.TimeoutException if it doesn't finish within the configured budget.
     */
    public int crawlSiteBlocking(String baseUrl, String categoryName)
            throws InterruptedException, java.util.concurrent.TimeoutException {
        String ssrfError = SsrfGuard.validate(baseUrl, scrapeAllowLoopback);
        if (ssrfError != null) {
            throw new IllegalStateException("Refused to crawl URL: " + ssrfError);
        }
        int maxPages = settingsService.getCrawlerMaxPages(250);
        List<String> formats = settingsService.getCrawlerFormats(List.of("markdown"));
        log.info("Sending crawl request for {} with limit {} and formats {}", baseUrl, maxPages, formats);

        // Force strict configuration pulling. Do not use 'changeTracking' as Firecrawl will omit
        // Markdown for 'unchanged' snapshots, breaking ingestion.
        CrawlRequest crawlRequest = new CrawlRequest(baseUrl, maxPages, new CrawlOptions(formats));
        CrawlInitResponse initResponse = restClient.post()
                .uri("/v1/crawl")
                .body(crawlRequest)
                .retrieve()
                .body(CrawlInitResponse.class);

        if (initResponse == null || !initResponse.success() || initResponse.id() == null) {
            throw new IllegalStateException("Firecrawl rejected the crawl request for " + baseUrl);
        }
        String jobId = initResponse.id();
        log.info("Crawl {} initialized for {}. Polling (timeout {}s)...", jobId, baseUrl, crawlTimeoutMs / 1000);

        java.util.Set<String> processedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet();
        long deadlineNanos = System.nanoTime() + crawlTimeoutMs * 1_000_000L;
        int consecutiveErrors = 0;

        while (true) {
            Thread.sleep(crawlPollIntervalMs);
            if (System.nanoTime() > deadlineNanos) {
                throw new java.util.concurrent.TimeoutException("Crawl " + jobId + " for " + baseUrl
                        + " exceeded " + (crawlTimeoutMs / 1000) + "s; ingested " + processedUrls.size()
                        + " pages before giving up");
            }

            CrawlStatusResponse status;
            try {
                status = restClient.get().uri("/v1/crawl/{jobId}", jobId).retrieve().body(CrawlStatusResponse.class);
            } catch (Exception e) {
                if (++consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
                    throw new IllegalStateException("Crawl " + jobId + " polling failed " + consecutiveErrors
                            + "x consecutively: " + e.getMessage(), e);
                }
                log.warn("Transient error polling crawl {} ({}/{}): {}", jobId, consecutiveErrors,
                        MAX_CONSECUTIVE_POLL_ERRORS, e.getMessage());
                continue;
            }
            consecutiveErrors = 0;

            if (status == null) {
                log.warn("Null status response while polling crawl {}", jobId);
                continue;
            }
            log.info("Crawl {} status={} progress={}/{}", jobId, status.status(), status.completed(), status.total());

            // Drain the current page plus every `next` cursor page into the KB in real time.
            drainAllPages(status, baseUrl, categoryName, processedUrls);

            String state = status.status();
            if ("completed".equalsIgnoreCase(state)) {
                log.info("Crawl {} completed. Ingested {} pages from {}", jobId, processedUrls.size(), baseUrl);
                return processedUrls.size();
            }
            if ("failed".equalsIgnoreCase(state) || "cancelled".equalsIgnoreCase(state)) {
                throw new IllegalStateException("Crawl " + jobId + " for " + baseUrl + " ended in state '"
                        + state + "' after ingesting " + processedUrls.size() + " pages");
            }
        }
    }

    /**
     * @summary Ingests the current status page's data, then follows Firecrawl's {@code next} cursor
     *     to drain every remaining result page (bounded by {@link #MAX_NEXT_PAGE_FOLLOWS}). Dedup by
     *     URL across polls makes re-seeing a page a cheap no-op.
     */
    private void drainAllPages(CrawlStatusResponse status, String baseUrl, String categoryName,
                               java.util.Set<String> processedUrls) {
        processCrawledData(status.data(), baseUrl, categoryName, processedUrls);
        String next = status.next();
        int follows = 0;
        while (next != null && !next.isBlank() && follows++ < MAX_NEXT_PAGE_FOLLOWS) {
            try {
                CrawlStatusResponse page = restClient.get().uri(URI.create(next)).retrieve().body(CrawlStatusResponse.class);
                if (page == null) break;
                processCrawledData(page.data(), baseUrl, categoryName, processedUrls);
                next = page.next();
            } catch (Exception e) {
                log.warn("Failed to follow crawl pagination cursor for {}: {}", baseUrl, e.getMessage());
                break;
            }
        }
    }

    /**
     * @summary Processes the scraped JSON payload from Firecrawl and maps it into PGVector representations.
     * @logic
     * Iterates over the raw ScrapeData structures returned by the Firecrawl API dynamically. Extracts `url` and `title` metadata, merging with fallbacks. Only submits clean markdown documents to the pipeline if not already present in the processedUrls HashSet.
     */
    private void processCrawledData(List<ScrapeData> dataList, String fallbackBaseUrl, String categoryName, java.util.Set<String> processedUrls) {
        if (dataList == null) {
            return;
        }
        log.debug("processCrawledData evaluating {} items from Firecrawl", dataList.size());
        
        int tickIngested = 0;
        for (ScrapeData data : dataList) {
            if (data.markdown() != null && !data.markdown().isBlank()) {
                String sourceUrl = fallbackBaseUrl;
                String title = "Scraped Document";
                
                if (data.metadata() != null) {
                    // Start with 'url' (unique page), fallback to 'sourceURL' (root), then fallbackBaseUrl
                    sourceUrl = (String) data.metadata().get("url");
                    if (sourceUrl == null || sourceUrl.isBlank()) {
                        sourceUrl = (String) data.metadata().getOrDefault("sourceURL", fallbackBaseUrl);
                    }
                    title = (String) data.metadata().getOrDefault("title", title);
                }
                
                // Fast Check: If we already processed this exact URL during this bulk Job loop, safely skip it instantly!
                if (!processedUrls.add(sourceUrl)) {
                    continue;
                }
                
                log.info("Extracting and ingesting document '{}' from source: {}", title, sourceUrl);
                try {
                    java.util.UUID kbId = knowledgeOperations.resolveCategoryId(categoryName, sourceUrl);
                    knowledgeOperations.ingestText(title, data.markdown(), sourceUrl, kbId);
                    tickIngested++;
                } catch (Exception e) {
                    log.warn("Skipped ingesting document '{}' ({}): {}", title, sourceUrl, e.getMessage());
                }
            }
        }
        
        if (tickIngested > 0) {
            log.info("Stream check: Ingested {} net-new pages into Knowledge Base from {}", tickIngested, fallbackBaseUrl);
        }
    }
}
