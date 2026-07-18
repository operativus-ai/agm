package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.KnowledgeContent;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.KnowledgeProcessingException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.operativus.agentmanager.core.model.MetadataKeys;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.VectorStoreClassifications;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.operativus.agentmanager.control.security.AgentSecurityFilters;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter.Expression;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Core ingestion pipeline for documents and web content into the Vector Database (PostgreSQL/pgvector).
 * State: Stateless
 */
@Service
public class KnowledgeService implements com.operativus.agentmanager.core.registry.KnowledgeOperations {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final VectorStore vectorStore;
    private final KnowledgeContentRepository knowledgeRepo;
    private final JdbcTemplate jdbcTemplate;
    private final com.operativus.agentmanager.control.repository.KnowledgeBaseRepository knowledgeBaseRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final SafetyService safetyService;
    private final IngestionStatusService ingestionStatusService;

    /**
     * SSRF guard escape hatch: when {@code true}, {@link #validateIngestionUrl} accepts
     * loopback / private / link-local addresses. Production default is {@code false}; the
     * test profile sets it to {@code true} in {@code application-test.properties} so
     * WireMock-backed runtime tests can ingest {@code http://localhost:<dynamic-port>}.
     * The {@code KnowledgeIngestUrlSsrfRuntimeTest} class pins this back to {@code false}
     * via {@code @TestPropertySource} to verify the strict policy.
     *
     * <p>The non-loopback SSRF rejections (cloud-metadata, public-but-private decimal-encoded
     * forms) always fire regardless of this flag — only the private/loopback predicates are
     * gated. See {@link #validateIngestionUrl}.</p>
     */
    private final boolean ingestAllowLoopback;
    /** Semantic-search retrieval knobs (SRE-tunable). Defaults: topK=4, similarityThreshold=0.4
     *  (data-backed 2026-07-04 for text-embedding-3-small — on-topic ~0.74-0.81 vs noise ~0.27-0.37;
     *  0.4 clears the noise ceiling well below the relevant floor). Set 0.0 for accept-all; raise
     *  toward 0.5 for stricter precision. Raise topK to widen the candidate pool for reranking. */
    private final int searchTopK;
    private final double searchSimilarityThreshold;

    public KnowledgeService(VectorStore vectorStore,
                            KnowledgeContentRepository knowledgeRepo,
                            JdbcTemplate jdbcTemplate,
                            com.operativus.agentmanager.control.repository.KnowledgeBaseRepository knowledgeBaseRepository,
                            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                            SafetyService safetyService,
                            IngestionStatusService ingestionStatusService,
                            @org.springframework.beans.factory.annotation.Value("${agent.knowledge.ingest.allow-loopback-urls:false}") boolean ingestAllowLoopback,
                            @org.springframework.beans.factory.annotation.Value("${agent.rag.search.top-k:4}") int searchTopK,
                            @org.springframework.beans.factory.annotation.Value("${agent.rag.search.similarity-threshold:0.4}") double searchSimilarityThreshold) {
        this.vectorStore = vectorStore;
        this.knowledgeRepo = knowledgeRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.objectMapper = objectMapper;
        this.safetyService = safetyService;
        this.ingestionStatusService = ingestionStatusService;
        this.ingestAllowLoopback = ingestAllowLoopback;
        this.searchTopK = searchTopK;
        this.searchSimilarityThreshold = searchSimilarityThreshold;
    }

    /**
     * @summary Resolves or creates a KnowledgeBase category ID by name.
     * @logic Searches the KnowledgeBaseRepository for an existing category name, creating and persisting a new one if not found.
     */
    @Override
    public java.util.UUID resolveCategoryId(String categoryName, String fallbackUrl) {
        String finalCategoryName = categoryName;
        if (finalCategoryName == null || finalCategoryName.trim().isEmpty()) {
            try {
                finalCategoryName = "Scraped " + java.net.URI.create(fallbackUrl.startsWith("http") ? fallbackUrl : "https://" + fallbackUrl).getHost();
            } catch (Exception e) {
                finalCategoryName = "Uncategorized Scrapes";
            }
        }
        
        final String searchName = finalCategoryName;
        // Tenant-scope the lookup: a KB named "Default" in org A and one named "Default" in
        // org B must resolve to two different rows. Falls back to DEFAULT_SYSTEM_ORG when no
        // authenticated context exists (system-background callers like the scraper agent).
        final String resolvedOrgId;
        {
            String ctxOrgId = AgentContextHolder.getOrgId();
            resolvedOrgId = (ctxOrgId == null || ctxOrgId.isBlank())
                    ? com.operativus.agentmanager.core.model.TenantConstants.DEFAULT_SYSTEM_ORG
                    : ctxOrgId;
        }
        return knowledgeBaseRepository.findByNameAndOrgId(searchName, resolvedOrgId)
                .map(com.operativus.agentmanager.core.entity.KnowledgeBase::getId)
                .orElseGet(() -> {
                    com.operativus.agentmanager.core.entity.KnowledgeBase newKb = new com.operativus.agentmanager.core.entity.KnowledgeBase(searchName, "Auto-generated collection from scraper");
                    newKb.setOrgId(resolvedOrgId);
                    return knowledgeBaseRepository.save(newKb).getId();
                });
    }



    /**
     * @summary Ingests a raw file byte array into the Vector Store.
     * @logic Creates and persists a 'PROCESSING' KnowledgeContent metadata record, extracts text using TikaDocumentReader, splits text via TokenTextSplitter, injects entity relationships into chunk metadata, saves chunks to VectorStore, and marks metadata as 'COMPLETED' or 'FAILED'.
     */
    public UUID ingest(String filename, String contentType, byte[] contentData, UUID knowledgeBaseId) {
        return ingest(filename, contentType, contentData, knowledgeBaseId, null);
    }

    public UUID ingest(String filename, String contentType, byte[] contentData, UUID knowledgeBaseId, String description) {
        log.info("Starting ingestion for file: {}, type: {}, size: {} bytes, kbId: {}", filename, contentType, contentData.length, knowledgeBaseId);
        // 1. Compute SHA-256 and check per-KB dedup
        String contentHash = sha256Hex(contentData);
        if (knowledgeRepo.existsByContentHashAndKnowledgeBaseId(contentHash, knowledgeBaseId)) {
            throw new com.operativus.agentmanager.core.exception.BusinessValidationException(
                    "Duplicate file: identical content already exists in this knowledge base.");
        }
        // 2. Create Metadata Entry
        KnowledgeContent contentEntity = new KnowledgeContent();
        contentEntity.setName(filename);
        contentEntity.setContentType(contentType);
        contentEntity.setSize(contentData.length);
        contentEntity.setContentHash(contentHash);
        contentEntity.setStatus(RunStatus.PROCESSING);
        contentEntity.setKnowledgeBaseId(knowledgeBaseId);
        if (description != null && !description.isBlank()) contentEntity.setDescription(description);
        contentEntity.setOwnerId(AgentContextHolder.getUserId());
        final KnowledgeContent savedEntity = knowledgeRepo.save(contentEntity);
        final UUID entityId = savedEntity.getId();
        final String orgId = AgentContextHolder.getOrgId();
        // F10 — fresh VTs do NOT inherit JDK 21 ScopedValues. Capture caller bindings so the
        // ingestion body sees the same orgId/userId/runId as the request thread (vector store
        // writes use orgId for tenant partitioning; without rebind a future caller reading
        // AgentContextHolder.* would see defaults).
        final com.operativus.agentmanager.core.callback.AgentContextSnapshot ingestSnapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        Thread.ofVirtual().start(() -> ingestSnapshot.run(() -> {
            try {
                Resource resource = new ByteArrayResource(contentData);
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<Document> documents = reader.read();

                TokenTextSplitter splitter = TokenTextSplitter.builder().build();
                List<Document> chunks = splitter.apply(documents);

                String resolvedOrgId = (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
                String timestamp = Instant.now().toString();
                List<Document> sanitizedChunks = new java.util.ArrayList<>();
                for (Document chunk : chunks) {
                    chunk.getMetadata().put(MetadataKeys.SOURCE_ID, entityId.toString());
                    chunk.getMetadata().put(MetadataKeys.ORG_ID, resolvedOrgId);
                    chunk.getMetadata().put(MetadataKeys.TIMESTAMP, timestamp);
                    chunk.getMetadata().put(MetadataKeys.SYSTEM_CLASSIFICATION, VectorStoreClassifications.INTERNAL);
                    chunk.getMetadata().put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_KB);
                    if (knowledgeBaseId != null) chunk.getMetadata().put(MetadataKeys.KNOWLEDGE_BASE_ID, knowledgeBaseId.toString());
                    Document safeChunk = sanitizeChunkForStorage(chunk);
                    if (safeChunk != null) sanitizedChunks.add(safeChunk);
                }
                log.debug("Adding {} chunks to VectorStore for file: {}", sanitizedChunks.size(), filename);
                vectorStore.add(sanitizedChunks);
                List<UUID> generatedVectorIds = sanitizedChunks.stream().map(c -> UUID.fromString(c.getId())).toList();
                knowledgeRepo.findById(entityId).ifPresent(toUpdate -> {
                    toUpdate.setStatus(RunStatus.COMPLETED);
                    toUpdate.setVectorIds(generatedVectorIds);
                    knowledgeRepo.save(toUpdate);
                });
                ingestionStatusService.emit(entityId, JobStatus.COMPLETED.getValue(), "Ingestion complete");
                log.info("Successfully ingested file: {}", filename);
            } catch (Exception e) {
                log.error("Ingestion failed for file {}: {}", filename, e.getMessage(), e);
                knowledgeRepo.findById(entityId).ifPresent(toUpdate -> {
                    toUpdate.setStatus(RunStatus.FAILED);
                    toUpdate.setStatusMessage("Ingestion failed: " + e.getMessage());
                    // Release the (content_hash, knowledge_base_id) slot in the partial unique
                    // index (migration 019, WHERE content_hash IS NOT NULL). Without this,
                    // the user cannot retry by re-uploading the same bytes — the dedup check at
                    // ingest() entry would reject. The /retry endpoint explicitly refuses file
                    // uploads, so clearing the hash here is the ONLY recovery path. Setting to
                    // NULL is safe because the partial index excludes NULL rows.
                    toUpdate.setContentHash(null);
                    knowledgeRepo.save(toUpdate);
                });
                ingestionStatusService.emit(entityId, JobStatus.FAILED.getValue(), e.getMessage());
            }
        }));
        return entityId;
    }

    /**
     * @summary Ingests raw markdown/text into the Vector Store (often bridged from the Scraper Agent).
     * @logic Validates URI uniqueness to prevent duplicate ingestions. Creates and persists a 'PROCESSING' KnowledgeContent metadata record. Splits content first by Markdown Headers (H2/H3) to preserve semantic sections, then applies TokenTextSplitter for strict token limits. Saves chunks to VectorStore and marks metadata as 'COMPLETED'.
     */
    public void ingestText(String title, String content, String sourceUrl, UUID knowledgeBaseId) {
        log.info("Starting text ingestion for title: {}, source: {}, kbId: {}", title, sourceUrl, knowledgeBaseId);

        if (sourceUrl != null && !sourceUrl.isEmpty() && knowledgeRepo.existsByUri(sourceUrl)) {
            throw new BusinessValidationException("Knowledge content with URI already exists: " + sourceUrl);
        }

        // 1. Create Metadata Entry
        KnowledgeContent contentEntity = new KnowledgeContent();
        contentEntity.setName(title);
        contentEntity.setStatus(RunStatus.PROCESSING);
        contentEntity.setContentType("text/markdown");
        contentEntity.setUri(sourceUrl);
        contentEntity.setKnowledgeBaseId(knowledgeBaseId);
        contentEntity.setOwnerId(AgentContextHolder.getUserId());
        final KnowledgeContent savedEntity = knowledgeRepo.save(contentEntity);
        final UUID entityId = savedEntity.getId();

        try {
                // 2. Pre-split by Markdown headers to keep contextual sections together
                List<Document> markdownSections = new java.util.ArrayList<>();
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(MetadataKeys.SOURCE_URL, sourceUrl);
                
                // Split by H2 or H3 headers (lookahead regex)
                String[] sections = content.split("(?=\\n\\n## |\\n\\n### )");
                for (String section : sections) {
                    if (!section.trim().isEmpty()) {
                        markdownSections.add(new Document(section.trim(), new HashMap<>(metadata)));
                    }
                }

                // 3. Split sections into Token Chunks if they still exceed max limits
                TokenTextSplitter splitter = TokenTextSplitter.builder().build();
                List<Document> chunks = splitter.apply(markdownSections);

                // 4. Store in Vector DB
                String orgId = AgentContextHolder.getOrgId();
                if (orgId == null || orgId.isBlank()) orgId = TenantConstants.DEFAULT_SYSTEM_ORG;
                String timestamp = Instant.now().toString();

                List<Document> sanitizedChunks = new java.util.ArrayList<>();
                for (Document chunk : chunks) {
                    chunk.getMetadata().put(MetadataKeys.SOURCE_ID, entityId.toString());
                    chunk.getMetadata().put(MetadataKeys.SOURCE_URL, sourceUrl); // Keep URL on chunks
                    chunk.getMetadata().put(MetadataKeys.ORG_ID, orgId);
                    chunk.getMetadata().put(MetadataKeys.TIMESTAMP, timestamp);
                    chunk.getMetadata().put(MetadataKeys.SYSTEM_CLASSIFICATION, VectorStoreClassifications.INTERNAL);
                    chunk.getMetadata().put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_KB);
                    if (knowledgeBaseId != null) {
                        chunk.getMetadata().put(MetadataKeys.KNOWLEDGE_BASE_ID, knowledgeBaseId.toString());
                    }
                    
                    Document safeChunk = sanitizeChunkForStorage(chunk);
                    if (safeChunk != null) sanitizedChunks.add(safeChunk);
                }
                log.debug("Adding {} text chunks to VectorStore for source: {}", sanitizedChunks.size(), sourceUrl);
                vectorStore.add(sanitizedChunks);

                List<UUID> generatedVectorIds = sanitizedChunks.stream()
                        .map(chunk -> UUID.fromString(chunk.getId()))
                        .toList();

                // 5. Update Status
                KnowledgeContent toUpdate = knowledgeRepo.findById(entityId).orElseThrow();
                    toUpdate.setStatus(RunStatus.COMPLETED);
                toUpdate.setVectorIds(generatedVectorIds);
                knowledgeRepo.save(toUpdate);
                log.info("Successfully ingested text mapped to source: {}", sourceUrl);

            } catch (Exception e) {
                log.error("Text ingestion failed for source {}: {}", sourceUrl, e.getMessage(), e);
                knowledgeRepo.findById(entityId).ifPresent(toUpdate -> {
                    toUpdate.setStatus(RunStatus.FAILED);
                    toUpdate.setStatusMessage("Text ingestion failed: " + e.getMessage());
                    knowledgeRepo.save(toUpdate);
                });
                throw new KnowledgeProcessingException("Text ingestion failed: " + e.getMessage(), e);
            }
    }

    /**
     * @summary Re-wraps a freshly-split chunk with sanitized content for vector storage.
     * @logic Applies {@code sanitizeForStorage} to the chunk text and rebuilds an immutable
     *     {@link Document}. Returns {@code null} when the content sanitizes to blank — Spring AI
     *     2.0.0-SNAPSHOT's Document rejects null/blank text ("exactly one of text or media must be
     *     specified"), and a fully-redacted chunk has no embeddable value, so callers skip nulls
     *     rather than letting one bad chunk fail the whole ingestion.
     */
    private Document sanitizeChunkForStorage(Document chunk) {
        String safeContent = safetyService.sanitizeForStorage(chunk.getText());
        if (safeContent == null || safeContent.isBlank()) {
            log.debug("Skipping chunk {} — empty after sanitization", chunk.getId());
            return null;
        }
        return new Document(chunk.getId(), safeContent, chunk.getMetadata());
    }

    /**
     * @summary Retrieves metadata records for ingested knowledge base items, scoped to the
     *     caller's tenant.
     * @logic Resolves callerOrgId from AgentContextHolder (with DEFAULT_SYSTEM_ORG fallback),
     *     fetches the caller's KnowledgeBase ids, then returns content rows whose
     *     knowledge_base_id is in that set. KnowledgeContent has no direct org_id column —
     *     the tenant boundary is enforced via the KB → org_id chain (matches the pattern
     *     already used by {@link #resolveCategoryId}).
     */
    public List<KnowledgeContent> listFiles() {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId == null || orgId.isBlank()) orgId = TenantConstants.DEFAULT_SYSTEM_ORG;
        List<java.util.UUID> kbIds = knowledgeBaseRepository.findByOrgId(orgId).stream()
                .map(com.operativus.agentmanager.core.entity.KnowledgeBase::getId)
                .toList();
        return kbIds.isEmpty() ? List.of() : knowledgeRepo.findByKnowledgeBaseIdIn(kbIds);
    }

    /**
     * @summary Retrieves a paginated set of metadata records for ingested knowledge base items,
     *     scoped to the caller's tenant.
     * @logic Resolves callerOrgId via the same fallback as the no-arg {@link #listFiles()}.
     *     The repository query joins KnowledgeContent → KnowledgeBase on knowledge_base_id and
     *     filters by KnowledgeBase.orgId; KnowledgeContent has no direct org_id column so the
     *     boundary is enforced via the parent KB.
     */
    public org.springframework.data.domain.Page<KnowledgeContent> listFiles(org.springframework.data.domain.Pageable pageable) {
        return knowledgeRepo.findAllByCallerOrgId(callerOrgId(), pageable);
    }

    public org.springframework.data.domain.Page<KnowledgeContent> listFilesByKnowledgeBase(UUID knowledgeBaseId, org.springframework.data.domain.Pageable pageable) {
        String orgId = callerOrgId();
        if (!knowledgeBaseRepository.existsByIdAndOrgId(knowledgeBaseId, orgId)) {
            throw new ResourceNotFoundException("KnowledgeBase", knowledgeBaseId.toString());
        }
        return knowledgeRepo.findByKnowledgeBaseIdAndCallerOrgId(knowledgeBaseId, orgId, pageable);
    }

    public KnowledgeContent getById(UUID id) {
        return knowledgeRepo.findByIdAndCallerOrgId(id, callerOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", id.toString()));
    }

    private String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }

    public UUID ingestUrlAsync(String url, UUID knowledgeBaseId) {
        String ssrfError = validateIngestionUrl(url, ingestAllowLoopback);
        if (ssrfError != null) {
            throw new BusinessValidationException(ssrfError);
        }
        if (knowledgeRepo.existsByUri(url)) {
            throw new BusinessValidationException("Knowledge content with URI already exists: " + url);
        }
        KnowledgeContent contentEntity = new KnowledgeContent();
        contentEntity.setName(url);
        contentEntity.setStatus(RunStatus.PROCESSING);
        contentEntity.setContentType("text/html");
        contentEntity.setUri(url);
        contentEntity.setKnowledgeBaseId(knowledgeBaseId);
        contentEntity.setOwnerId(AgentContextHolder.getUserId());
        final KnowledgeContent saved = knowledgeRepo.save(contentEntity);
        final UUID entityId = saved.getId();
        launchUrlFetchOnVirtualThread(entityId, url, knowledgeBaseId);
        return entityId;
    }

    /**
     * @summary Retry a failed URL-sourced ingestion. File uploads cannot be retried because the
     *     raw bytes are not persisted after the first ingest attempt — only their derived chunks
     *     are stored in vectors. URL ingestion can be retried because the source URL is still on
     *     {@code KnowledgeContent.uri} and the page is still fetchable.
     * @logic Loads the existing {@link KnowledgeContent} entity. Returns false (caller maps to 422
     *     {@code retry_not_supported_for_file_uploads}) when the entity has no URL or has a URL
     *     that doesn't look HTTP(S). On a valid retry, resets the entity's status to PROCESSING,
     *     clears the failure statusMessage and any partial vectorIds, then re-launches the same
     *     virtual-thread Jsoup fetch path used by initial URL ingestion.
     */
    public boolean retryFailedUrlIngestion(UUID id) {
        KnowledgeContent existing = knowledgeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document", id.toString()));
        if (existing.getStatus() != RunStatus.FAILED) {
            throw new com.operativus.agentmanager.core.exception.BusinessValidationException(
                    "Document is not in FAILED status (current: " + existing.getStatus() + ")");
        }
        String url = existing.getUri();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return false;
        }
        String ssrfError = validateIngestionUrl(url, ingestAllowLoopback);
        if (ssrfError != null) {
            throw new BusinessValidationException(ssrfError);
        }
        existing.setStatus(RunStatus.PROCESSING);
        existing.setStatusMessage(null);
        existing.setVectorIds(null);
        knowledgeRepo.save(existing);
        ingestionStatusService.emit(id, JobStatus.PROCESSING.getValue(), "Retry triggered");
        launchUrlFetchOnVirtualThread(id, url, existing.getKnowledgeBaseId());
        return true;
    }

    private void launchUrlFetchOnVirtualThread(UUID entityId, String url, UUID knowledgeBaseId) {
        final String orgId = AgentContextHolder.getOrgId();
        // F10 — same as the byte-array ingest above: rebind caller bindings on the VT so
        // anything reading AgentContextHolder.* during the URL fetch sees the right tenant.
        final com.operativus.agentmanager.core.callback.AgentContextSnapshot urlFetchSnapshot =
                com.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        Thread.ofVirtual().start(() -> urlFetchSnapshot.run(() -> {
            try {
                org.jsoup.nodes.Document jsoupDoc = org.jsoup.Jsoup.connect(url)
                        .userAgent("Mozilla/5.0")
                        .timeout(30000)
                        .get();
                String title = jsoupDoc.title();
                String bodyText = jsoupDoc.body().text();
                String fullContent = "Title: " + title + "\nSource: " + url + "\n\n" + bodyText;
                TokenTextSplitter splitter = TokenTextSplitter.builder().build();
                List<Document> chunks = splitter.apply(List.of(new Document(fullContent)));
                String resolvedOrgId = (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
                String timestamp = Instant.now().toString();
                List<Document> sanitizedChunks = new java.util.ArrayList<>();
                for (Document chunk : chunks) {
                    chunk.getMetadata().put(MetadataKeys.SOURCE_ID, entityId.toString());
                    chunk.getMetadata().put(MetadataKeys.ORG_ID, resolvedOrgId);
                    chunk.getMetadata().put(MetadataKeys.TIMESTAMP, timestamp);
                    chunk.getMetadata().put(MetadataKeys.SYSTEM_CLASSIFICATION, VectorStoreClassifications.INTERNAL);
                    chunk.getMetadata().put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_KB);
                    if (knowledgeBaseId != null) chunk.getMetadata().put(MetadataKeys.KNOWLEDGE_BASE_ID, knowledgeBaseId.toString());
                    Document safeChunk = sanitizeChunkForStorage(chunk);
                    if (safeChunk != null) sanitizedChunks.add(safeChunk);
                }
                vectorStore.add(sanitizedChunks);
                List<UUID> generatedVectorIds = sanitizedChunks.stream().map(c -> UUID.fromString(c.getId())).toList();
                knowledgeRepo.findById(entityId).ifPresent(c -> {
                    c.setStatus(RunStatus.COMPLETED);
                    c.setVectorIds(generatedVectorIds);
                    knowledgeRepo.save(c);
                });
                ingestionStatusService.emit(entityId, JobStatus.COMPLETED.getValue(), "URL ingestion complete");
                log.info("URL ingestion complete for: {}", url);
            } catch (Exception e) {
                log.error("URL ingestion failed for {}: {}", url, e.getMessage(), e);
                knowledgeRepo.findById(entityId).ifPresent(c -> {
                    c.setStatus(RunStatus.FAILED);
                    c.setStatusMessage("Ingestion failed: " + e.getMessage());
                    knowledgeRepo.save(c);
                });
                ingestionStatusService.emit(entityId, JobStatus.FAILED.getValue(), e.getMessage());
            }
        }));
    }

    /**
     * @summary Deletes a KnowledgeContent record and all associated vector chunks.
     * @logic Loads vectorIds from the metadata record, deletes matching chunks from VectorStore first, then removes the metadata record. The @Transactional boundary ensures full rollback if vectorStore.delete() throws — no partial state is possible.
     */
    @Transactional
    public void delete(UUID id) {
        log.info("Deleting knowledge document ID: {}", id);
        KnowledgeContent content = knowledgeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document", id.toString()));
        List<UUID> vectorIds = content.getVectorIds();
        if (vectorIds != null && !vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds.stream().map(UUID::toString).toList());
            log.debug("Deleted {} vector chunks for document ID: {}", vectorIds.size(), id);
        }
        knowledgeRepo.deleteById(id);
        log.debug("Successfully deleted knowledge metadata for ID: {}", id);
    }
    
    /**
     * @summary Directly queries the underlying Vector DB schema for raw chunks associated with a source.
     * @logic Executes a raw JDBC query against the 'vector_store' table extracting 'content' and 'metadata', filtering by the 'source_id' JSON metadata field.
     */
    public List<Map<String, Object>> getDocumentChunks(UUID sourceId) {
        log.debug("Retrieving vector chunks for source ID: {}", sourceId);
        String sql = "SELECT content, metadata FROM vector_store WHERE metadata->>'sourceId' = ?";
        return jdbcTemplate.queryForList(sql, sourceId.toString());
    }

    /**
     * @summary Performs a semantic similarity search across all ingested content vectors.
     * @logic Delegates directly to the Spring AI VectorStore implementation with native RLAC expression builder.
     */
    @Transactional
    public List<Document> search(String query) {
        return search(query, searchTopK);
    }

    /**
     * @summary Semantic similarity search with an explicit candidate-pool size.
     * @logic Overload used by {@code AdvancedRagAdvisor} to pull a larger candidate pool (its
     *        candidatePoolSize) into the hybrid RRF + rerank stage, rather than the default
     *        retrieval topK. The 1-arg {@link #search(String)} delegates here with {@code searchTopK}.
     */
    @Transactional
    public List<Document> search(String query, int topK) {
        log.debug("Performing semantic vector search for query: [{}] (topK={})", query, topK);
        Expression securityFilter = AgentSecurityFilters.buildVectorFilter();
        SearchRequest.Builder req = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(searchSimilarityThreshold);
        if (securityFilter != null) {
            req.filterExpression(securityFilter);
        }
        List<Document> results = vectorStore.similaritySearch(req.build());
        List<UUID> sourceIds = results.stream()
                .map(doc -> doc.getMetadata().get(MetadataKeys.SOURCE_ID))
                .filter(java.util.Objects::nonNull)
                .map(id -> UUID.fromString(id.toString()))
                .distinct()
                .toList();
        if (!sourceIds.isEmpty()) {
            knowledgeRepo.incrementAccessCount(sourceIds);
        }
        return results;
    }
    
    /**
     * @summary Performs a full-text hybrid keyword search using Postgres tsvector.
     * @logic Executes raw JDBC against the content_tsv column using websearch_to_tsquery for natural language support and overrides filtering with the active Security context.
     */
    /**
     * Moves a document to a different knowledge base by updating the KnowledgeContent record
     * and patching the knowledgeBaseId key in all vector chunk metadata via JDBC jsonb_set().
     * Avoids delete+re-add to preserve embeddings and prevent chunk loss on re-add failure.
     */
    @Transactional
    public void moveDocument(UUID documentId, UUID targetKbId) {
        log.info("Moving document {} to KB {}", documentId, targetKbId);
        if (!knowledgeBaseRepository.existsById(targetKbId)) {
            throw new ResourceNotFoundException("KnowledgeBase", targetKbId.toString());
        }
        KnowledgeContent content = knowledgeRepo.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId.toString()));
        content.setKnowledgeBaseId(targetKbId);
        knowledgeRepo.save(content);
        jdbcTemplate.update(
                "UPDATE vector_store SET metadata = jsonb_set(metadata, '{knowledgeBaseId}', ?::jsonb) WHERE metadata->>'sourceId' = ?",
                "\"" + targetKbId + "\"",
                documentId.toString()
        );
        log.debug("Moved document {} to KB {}", documentId, targetKbId);
    }

    private String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public List<Document> keywordSearch(String query, int limit) {
        log.debug("Performing full-text keyword search for query: [{}]", query);
        
        // Native RBAC filter override for SQL statement.
        Expression exp = AgentSecurityFilters.buildVectorFilter();
        String sql;
        Object[] params;
        
        if (exp != null) {
            String orgId = AgentContextHolder.getOrgId();
            // AND storeType='KB' (mirrors the vector-search filter) so KB keyword search
            // doesn't return memory entries from the shared vector_store.
            sql = "SELECT id, content, metadata FROM vector_store WHERE content_tsv @@ websearch_to_tsquery('english', ?) AND metadata->>'orgId' = ? AND metadata->>'storeType' = 'KB' LIMIT ?";
            params = new Object[]{query, orgId, limit};
        } else {
            sql = "SELECT id, content, metadata FROM vector_store WHERE content_tsv @@ websearch_to_tsquery('english', ?) LIMIT ?";
            params = new Object[]{query, limit};
        }

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String content = rs.getString("content");
            String metaJson = rs.getString("metadata");
            Map<String, Object> metadata = new HashMap<>();
            
            // Use injected ObjectMapper
            try {
                if (metaJson != null && !metaJson.isEmpty()) {
                    metadata = objectMapper.readValue(metaJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
            } catch (Exception e) {
                log.warn("Failed to parse metadata JSON for keyword search document", e);
            }
            
            Document doc = new Document(content, metadata);
            // In Spring AI 1.1.x, Document IDs are generated automatically, but you can set it via builder. 
            // We just use the 2-arg constructor since this is just used for RAG context extraction.
            return doc;
        }, params);
    }

    /**
     * SSRF guard for KB URL ingestion. Delegates to the shared
     * {@link com.operativus.agentmanager.core.security.SsrfGuard#validate} so all
     * outbound-URL callers in the codebase share one policy.
     */
    static String validateIngestionUrl(String rawUrl, boolean allowLoopback) {
        return com.operativus.agentmanager.core.security.SsrfGuard.validate(rawUrl, allowLoopback);
    }
}
