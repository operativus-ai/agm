package ai.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.compute.service.WebsiteCrawlerService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.SecurityPrincipals;
import ai.operativus.agentmanager.core.model.TenantConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Unit test for {@link WebsiteCrawlJobHandler}. Pins the two contracts that
 * make the queue migration correct: (1) the crawl is driven from the payload and its page count is
 * recorded on the job result; (2) tenant context (orgId/userId) is re-bound into
 * {@link AgentContextHolder} for the duration of the crawl, because the queue worker thread carries
 * none of the enqueuing request's ScopedValues — without this, KB ingestion would write under the
 * wrong (or default) org.
 * State: Stateless.
 */
class WebsiteCrawlJobHandlerTest {

    private final WebsiteCrawlerService crawler = mock(WebsiteCrawlerService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebsiteCrawlJobHandler handler = new WebsiteCrawlJobHandler(crawler, objectMapper);

    private BackgroundJob jobWith(String baseUrl, String category, String orgId, String userId) throws Exception {
        String payload = objectMapper.writeValueAsString(
                new WebsiteCrawlJobHandler.Payload(baseUrl, category, orgId, userId));
        return new BackgroundJob("job-1", null, WebsiteCrawlJobHandler.JOB_TYPE, payload);
    }

    @Test
    void execute_happyPath_crawlsFromPayloadAndRecordsPageCount() throws Exception {
        when(crawler.crawlSiteBlocking(eq("https://docs.example.com"), eq("Docs"))).thenReturn(7);

        BackgroundJob job = jobWith("https://docs.example.com", "Docs", "org-a", "user-1");
        handler.execute(job);

        verify(crawler).crawlSiteBlocking("https://docs.example.com", "Docs");
        assertTrue(job.getResult().contains("7"), "result should report the ingested page count, got: " + job.getResult());
        assertTrue(job.getResult().contains("https://docs.example.com"), "result should name the crawled site");
    }

    @Test
    void execute_rebindsTenantContextDuringCrawl() throws Exception {
        String[] seen = new String[2];
        when(crawler.crawlSiteBlocking(eq("https://x.com"), eq("X"))).thenAnswer(inv -> {
            seen[0] = AgentContextHolder.getOrgId();
            seen[1] = AgentContextHolder.getUserId();
            return 1;
        });

        handler.execute(jobWith("https://x.com", "X", "org-tenant-9", "alice"));

        assertEquals("org-tenant-9", seen[0], "orgId from payload must be bound for the crawl");
        assertEquals("alice", seen[1], "userId from payload must be bound for the crawl");
    }

    @Test
    void execute_nullTenantContext_coalescesToSystemDefaults() throws Exception {
        String[] seen = new String[2];
        when(crawler.crawlSiteBlocking(eq("https://y.com"), eq(null))).thenAnswer(inv -> {
            seen[0] = AgentContextHolder.getOrgId();
            seen[1] = AgentContextHolder.getUserId();
            return 0;
        });

        handler.execute(jobWith("https://y.com", null, null, null));

        assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, seen[0], "null orgId must coalesce to the system org");
        assertEquals(SecurityPrincipals.SYSTEM_PRINCIPAL, seen[1], "null userId must coalesce to the system principal");
    }
}
