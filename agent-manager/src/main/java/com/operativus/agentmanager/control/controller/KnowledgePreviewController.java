package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import com.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import com.operativus.agentmanager.control.service.KnowledgeService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.KnowledgeContent;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.TenantConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Document preview endpoints for inspecting ingested knowledge content and chunk boundaries.
 * Tenant-scoped: a content row's parent {@code knowledge_base.org_id} is compared to the
 * caller's {@code orgId}; mismatches return 404 (existence-leak protection).
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgePreviewController {

    private final KnowledgeContentRepository contentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeService knowledgeService;

    public KnowledgePreviewController(KnowledgeContentRepository contentRepository,
                                      KnowledgeBaseRepository knowledgeBaseRepository,
                                      KnowledgeService knowledgeService) {
        this.contentRepository = contentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeService = knowledgeService;
    }

    /**
     * Returns a preview of a knowledge document including metadata and chunk summary.
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> previewDocument(@PathVariable UUID id) {
        KnowledgeContent doc = resolveOrThrow(id);

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("id", doc.getId());
        preview.put("name", doc.getName());
        preview.put("description", doc.getDescription());
        preview.put("contentType", doc.getContentType());
        preview.put("uri", doc.getUri());
        preview.put("size", doc.getSize());
        preview.put("status", doc.getStatus());
        preview.put("statusMessage", doc.getStatusMessage());
        preview.put("metadata", doc.getMetadata());
        preview.put("chunkCount", doc.getVectorIds() != null ? doc.getVectorIds().size() : 0);
        preview.put("createdAt", doc.getCreatedAt());
        preview.put("updatedAt", doc.getUpdatedAt());

        return ResponseEntity.ok(preview);
    }

    /**
     * Returns the individual chunks (vector embeddings) for a document.
     * Each chunk includes its text content and metadata.
     */
    @GetMapping("/{id}/chunks/detail")
    public ResponseEntity<Map<String, Object>> getChunkDetails(@PathVariable UUID id) {
        KnowledgeContent doc = resolveOrThrow(id);

        List<UUID> vectorIds = doc.getVectorIds();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", doc.getId());
        result.put("documentName", doc.getName());
        result.put("totalChunks", vectorIds != null ? vectorIds.size() : 0);

        List<Map<String, Object>> chunks = knowledgeService.getDocumentChunks(id);
        result.put("chunks", chunks);

        return ResponseEntity.ok(result);
    }

    /**
     * Resolves a KnowledgeContent by id and enforces tenant isolation: the parent KB's
     * {@code orgId} must match the caller's. Cross-tenant lookups throw the same
     * {@link ResourceNotFoundException} as a missing id, so callers cannot distinguish
     * "doesn't exist" from "exists but not yours".
     */
    private KnowledgeContent resolveOrThrow(UUID id) {
        KnowledgeContent doc = contentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeContent", id.toString()));

        UUID kbId = doc.getKnowledgeBaseId();
        if (kbId != null) {
            String callerOrgId = resolveCallerOrgId();
            boolean ownedByCaller = knowledgeBaseRepository.existsByIdAndOrgId(kbId, callerOrgId);
            if (!ownedByCaller) {
                throw new ResourceNotFoundException("KnowledgeContent", id.toString());
            }
        }
        return doc;
    }

    private static String resolveCallerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }
}
