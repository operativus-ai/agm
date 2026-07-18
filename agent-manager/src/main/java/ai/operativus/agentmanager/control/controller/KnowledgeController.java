package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.service.IngestionStatusService;
import ai.operativus.agentmanager.control.service.KnowledgeService;
import ai.operativus.agentmanager.core.entity.KnowledgeContent;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Exposes REST APIs for uploading, searching, and managing individual Knowledge Documents (Content).
 * State: Stateless
 * Dependencies: KnowledgeService
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;
    private final IngestionStatusService ingestionStatusService;

    public KnowledgeController(KnowledgeService knowledgeService, IngestionStatusService ingestionStatusService) {
        this.knowledgeService = knowledgeService;
        this.ingestionStatusService = ingestionStatusService;
    }

    /**
     * @summary Retrieves a paginated list of metadata for all ingested documents.
     * @logic
     * - Accepts Spring Data Pageable for page/size/sort query parameters.
     * - Calls KnowledgeService to retrieve a Page of file definitions.
     */
    @GetMapping
    public org.springframework.data.domain.Page<KnowledgeContent> list(
            @RequestParam(required = false) UUID knowledgeBaseId,
            @org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        log.debug("Listing knowledge content, kbId={}, pageable={}", knowledgeBaseId, pageable);
        if (knowledgeBaseId != null) {
            return knowledgeService.listFilesByKnowledgeBase(knowledgeBaseId, pageable);
        }
        return knowledgeService.listFiles(pageable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeContent> getById(@PathVariable UUID id) {
        log.debug("Fetching knowledge document ID: {}", id);
        return ResponseEntity.ok(knowledgeService.getById(id));
    }

    /**
     * @summary Deletes an ingested document from the system.
     * @logic
     * - Instructs KnowledgeService to remove vector chunks and metadata logic for the given UUID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        log.info("Deleting knowledge id: {}", id);
        knowledgeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary Fetches the structural chunks mapped to a specific document for inspection.
     * @logic
     * - Queries PGVector indirectly to return the fragmented payload of the document.
     */
    @GetMapping("/{id}/chunks")
    public ResponseEntity<List<Map<String, Object>>> getChunks(@PathVariable UUID id) {
        log.debug("Received request to fetch chunks for knowledge id: {}", id);
        return ResponseEntity.ok(knowledgeService.getDocumentChunks(id));
    }

    /**
     * @summary Performs a raw semantic vector search across the knowledge base.
     * @logic
     * - Forwards the text query to the KnowledgeService for similarity lookup.
     */
    @GetMapping("/search")
    public List<org.springframework.ai.document.Document> search(@RequestParam String query) {
        return knowledgeService.search(query);
    }

    @PostMapping("/upload-batch")
    public ResponseEntity<ai.operativus.agentmanager.control.controller.model.UploadBatchResponse> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "knowledgeBaseId", required = false) UUID knowledgeBaseId,
            @RequestParam(value = "description", required = false) String description) {
        log.info("Received batch upload request: {} files, kbId: {}", files.size(), knowledgeBaseId);
        List<String> accepted = new java.util.ArrayList<>();
        List<String> rejected = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) { rejected.add(file.getOriginalFilename() + ": empty"); continue; }
            try {
                String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
                UUID documentId = knowledgeService.ingest(file.getOriginalFilename(), contentType, file.getBytes(), knowledgeBaseId, description);
                accepted.add(documentId.toString());
            } catch (ai.operativus.agentmanager.core.exception.BusinessValidationException e) {
                rejected.add(file.getOriginalFilename() + ": " + e.getMessage());
            } catch (Exception e) {
                log.error("Batch upload failed for file {}: {}", file.getOriginalFilename(), e.getMessage());
                rejected.add(file.getOriginalFilename() + ": ingestion failed");
            }
        }
        return ResponseEntity.accepted().body(new ai.operativus.agentmanager.control.controller.model.UploadBatchResponse(accepted, rejected));
    }

    /**
     * @summary Retry a failed URL-sourced ingestion. Returns 202 on retry launch, 422 when the
     *     document is a file upload (raw bytes are not persisted; user must re-upload), 400
     *     when the document is not in FAILED status, 404 when the document does not exist.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<java.util.Map<String, String>> retryIngestion(@PathVariable UUID id) {
        try {
            boolean retryLaunched = knowledgeService.retryFailedUrlIngestion(id);
            if (!retryLaunched) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(java.util.Map.of("reason", "retry_not_supported_for_file_uploads"));
            }
            return ResponseEntity.accepted().body(java.util.Map.of("status", "retrying"));
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (ai.operativus.agentmanager.core.exception.BusinessValidationException notFailed) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
                    .body(java.util.Map.of("reason", "not_in_failed_status"));
        }
    }

    @GetMapping(value = "/{id}/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamIngestionStatus(@PathVariable UUID id) {
        SseEmitter emitter = ingestionStatusService.register(id);
        // Emit current status immediately for late subscribers (processing finished before subscribe)
        try {
            KnowledgeContent doc = knowledgeService.getById(id);
            RunStatus status = doc.getStatus();
            if (status == RunStatus.COMPLETED || status == RunStatus.FAILED) {
                ingestionStatusService.emit(id, status.getValue(), doc.getStatusMessage() != null ? doc.getStatusMessage() : "");
            }
        } catch (Exception e) {
            log.debug("Could not check initial status for SSE stream on doc {}: {}", id, e.getMessage());
        }
        return emitter;
    }

    @PatchMapping("/{id}/move")
    public ResponseEntity<Void> move(
            @PathVariable UUID id,
            @RequestBody ai.operativus.agentmanager.control.controller.model.MoveDocumentRequest request) {
        log.info("Moving document {} to KB {}", id, request.knowledgeBaseId());
        knowledgeService.moveDocument(id, request.knowledgeBaseId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ingest-url")
    public ResponseEntity<Map<String, String>> ingestUrl(
            @RequestParam String url,
            @RequestParam(required = false) UUID knowledgeBaseId) {
        log.info("Received URL ingestion request: {}, kbId: {}", url, knowledgeBaseId);
        UUID documentId = knowledgeService.ingestUrlAsync(url, knowledgeBaseId);
        return ResponseEntity.accepted().body(Map.of("documentId", documentId.toString(), "status", "PROCESSING"));
    }
}
