package ai.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.compute.service.WebsiteCrawlerService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.SecurityPrincipals;
import ai.operativus.agentmanager.core.model.TenantConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: Runs a recursive website crawl + Knowledge-Base ingestion as a tracked,
 *     retryable background job, replacing the prior fire-and-forget Virtual-Thread path in
 *     {@code WebsiteCrawlerService.crawlAndIngestSiteAsync}. Moving the crawl into the persistent
 *     queue gives it a durable {@code background_jobs} row, UI-visible status/progress, retry on
 *     failure, and survival across a restart — none of which the old detached VT had.
 * State: Stateless.
 *
 * <p><b>Tenant context.</b> The job executes later on a queue-worker thread that does not carry the
 * enqueuing request's {@code AgentContextHolder} ScopedValues. The crawl's KB-ingestion path
 * ({@code KnowledgeService.ingestText}) reads {@code orgId}/{@code userId} from
 * {@link AgentContextHolder} for tenant partitioning, so we serialise them into the payload at
 * enqueue time and re-bind them here before crawling.</p>
 */
@Component
public class WebsiteCrawlJobHandler implements JobHandler {

    public static final String JOB_TYPE = "WEBSITE_CRAWL";

    private static final Logger log = LoggerFactory.getLogger(WebsiteCrawlJobHandler.class);

    private final WebsiteCrawlerService websiteCrawlerService;
    private final ObjectMapper objectMapper;

    public WebsiteCrawlJobHandler(WebsiteCrawlerService websiteCrawlerService, ObjectMapper objectMapper) {
        this.websiteCrawlerService = websiteCrawlerService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);

        // Re-bind tenant context captured at enqueue time; coalesce to non-null because
        // ScopedValue.where rejects null (KnowledgeService treats DEFAULT_SYSTEM_ORG as "no org").
        String orgId = (payload.orgId() == null || payload.orgId().isBlank())
                ? TenantConstants.DEFAULT_SYSTEM_ORG : payload.orgId();
        String userId = (payload.userId() == null || payload.userId().isBlank())
                ? SecurityPrincipals.SYSTEM_PRINCIPAL : payload.userId();

        log.info("Starting WEBSITE_CRAWL job {} for baseUrl={} category={} orgId={}",
                job.getId(), payload.baseUrl(), payload.categoryName(), orgId);

        int ingested = ScopedValue.where(AgentContextHolder.orgId, orgId)
                .where(AgentContextHolder.userId, userId)
                .call(() -> websiteCrawlerService.crawlSiteBlocking(payload.baseUrl(), payload.categoryName()));

        job.setResult("Crawled " + payload.baseUrl() + " — ingested " + ingested + " page(s) into the Knowledge Base"
                + (payload.categoryName() != null ? " under '" + payload.categoryName() + "'" : ""));
        log.info("WEBSITE_CRAWL job {} complete: {} pages from {}", job.getId(), ingested, payload.baseUrl());
    }

    public record Payload(String baseUrl, String categoryName, String orgId, String userId) {}
}
