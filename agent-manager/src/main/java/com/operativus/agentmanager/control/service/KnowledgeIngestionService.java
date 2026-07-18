package com.operativus.agentmanager.control.service;

import org.springframework.ai.document.Document;
import com.operativus.agentmanager.core.exception.KnowledgeProcessingException;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.nio.charset.Charset;
import java.util.List;
import java.time.Instant;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetadataKeys;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.VectorStoreClassifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Domain Responsibility: Handles the ingestion, splitting, and vectorization of unstructured knowledge inputs (URLs, Resouces).
 * State: Stateless
 */
@Service
public class KnowledgeIngestionService implements com.operativus.agentmanager.core.registry.KnowledgeIngestionOperations {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private final VectorStore vectorStore;
    private final SafetyService safetyService;

    public KnowledgeIngestionService(VectorStore vectorStore, SafetyService safetyService) {
        this.vectorStore = vectorStore;
        this.safetyService = safetyService;
    }

    /**
     * @summary Ingests unstructured text resource into the vector store with a strict 60-second timeout.
     * @logic Asynchronously reads resource content as String, processes and stores content, and enforces a strict 60-second limit matching frontend expectations.
     */
    public void ingest(Resource resource) {
        String orgId = AgentContextHolder.getOrgId();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Enforce strict 60-second limit matching frontend expectations
            var future = executor.submit(() -> {
                try {
                    String content = resource.getContentAsString(Charset.defaultCharset());
                    processAndStore(content, resource.getDescription(), orgId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Ingestion timed out strictly after 60 seconds for resource: {}", resource.getDescription());
            throw new KnowledgeProcessingException("Ingestion timed out after 60 seconds. The document may be too large or the database is unresponsive.", e);
        } catch (ExecutionException e) {
            throw new KnowledgeProcessingException("Failed to ingest resource", e.getCause());
        } catch (Exception e) {
            throw new KnowledgeProcessingException("Failed to ingest resource", e);
        }
    }

    /**
     * @summary Scrapes content from a specified URL and ingests it into the vector store.
     * @logic Fetches URL content using Jsoup with a 30-second timeout, extracts page title and body text, formats content, and calls processAndStore with retry support.
     */
    public void ingestUrl(String url) {
        log.info("Ingesting content from URL: {}", url);
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(30000) // 30-second timeout
                    .get();

            // Extract main content - simplistic heuristic
            String title = doc.title();
            String bodyText = doc.body().text();
            
            String fullContent = "Title: " + title + "\nSource: " + url + "\n\n" + bodyText;
            
            String orgId = AgentContextHolder.getOrgId();
            processAndStore(fullContent, url, orgId);
            log.info("Successfully ingested content from {}", url);

        } catch (Exception e) {
            log.error("Failed to ingest URL: {}", url, e);
            throw new KnowledgeProcessingException("Failed to ingest URL: " + url, e);
        }
    }

    /**
     * @summary Tokenizes and vectors plain text content for RAG usage.
     * @logic Creates a Document entity with source metadata, applies TokenTextSplitter to chunk the document, and persists the chunks into the VectorStore (pgvector).
     */
    private void processAndStore(String content, String source, String orgId) {
        Document doc = new Document(content);
        doc.getMetadata().put("source", source);
        
        // Split
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        List<Document> splitDocs = splitter.apply(List.of(doc));
        
        // Add to VectorStore with Security/Safety enforcement
        String finalOrgId = (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
        String timestamp = Instant.now().toString();
        List<Document> sanitizedChunks = new java.util.ArrayList<>();
        
        for (int i = 0; i < splitDocs.size(); i++) {
            Document chunk = splitDocs.get(i);
            // Stable ID derived from source + position: same URL always produces same chunk IDs.
            // PgVectorStore uses ON CONFLICT (id) DO NOTHING, so re-ingesting the same URL on retry
            // skips already-stored chunks without creating duplicates.
            String stableId = UUID.nameUUIDFromBytes((source + "_chunk_" + i).getBytes(StandardCharsets.UTF_8)).toString();

            chunk.getMetadata().put(MetadataKeys.ORG_ID, finalOrgId);
            chunk.getMetadata().put(MetadataKeys.TIMESTAMP, timestamp);
            chunk.getMetadata().put(MetadataKeys.SYSTEM_CLASSIFICATION, VectorStoreClassifications.INTERNAL);
            chunk.getMetadata().put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_KB);

            String safeContent = safetyService.sanitizeForStorage(chunk.getText());
            Document safeChunk = new Document(stableId, safeContent, chunk.getMetadata());
            sanitizedChunks.add(safeChunk);
        }
        
        vectorStore.add(sanitizedChunks);
    }
}
