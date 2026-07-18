package com.operativus.agentmanager.service;

import com.operativus.agentmanager.compute.tools.WebScraperTool;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WebsiteCrawlerServiceIT {

    private final WebScraperTool scraperTool;

    public WebsiteCrawlerServiceIT(WebScraperTool scraperTool) {
        this.scraperTool = scraperTool;
    }

    @Test
    void testBulkIngestion() throws InterruptedException {
        System.out.println("Starting bulk ingestion of Operativus Docs...");
        scraperTool.bulkIngestDocumentationSite("https://docs.agno.com/agent-os/introduction", "Operativus Docs");
        System.out.println("Waiting 60 seconds for async ingestion to complete...");
        Thread.sleep(60000);
        System.out.println("Finished test");
    }
}
