package ai.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.controller.model.AgentSummary;
import ai.operativus.agentmanager.control.dto.KnowledgeBaseRequest;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.control.service.queue.KnowledgeBaseDeletionJobHandler;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.KnowledgeBase;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import ai.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import ai.operativus.agentmanager.control.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain Responsibility: Exposes REST APIs for managing Knowledge Bases (Collections).
 *   Tenant-scoped: every read and write is filtered by the caller's {@code orgId} resolved
 *   from {@link AgentContextHolder#getOrgId()}. Cross-tenant lookups return 404 (not 403)
 *   to avoid existence leaks.
 * State: Stateless
 * Dependencies: KnowledgeBaseRepository, KnowledgeBaseService
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseRepository repository;
    private final KnowledgeContentRepository knowledgeContentRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final PersistentJobQueueService jobQueueService;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseController(KnowledgeBaseRepository repository,
                                   KnowledgeContentRepository knowledgeContentRepository,
                                   KnowledgeBaseService knowledgeBaseService,
                                   PersistentJobQueueService jobQueueService,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.knowledgeContentRepository = knowledgeContentRepository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.jobQueueService = jobQueueService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<KnowledgeBase> getAll() {
        List<KnowledgeBase> bases = repository.findByOrgId(callerOrgId());
        bases.forEach(kb -> kb.setDocumentCount((int) knowledgeContentRepository.countByKnowledgeBaseId(kb.getId())));
        return bases;
    }

    @PostMapping
    public KnowledgeBase create(@Valid @RequestBody KnowledgeBaseRequest request) {
        // Build the entity from the DTO. The id is left null so Hibernate's
        // @GeneratedValue assigns a fresh UUID — closes the mass-assignment vector
        // that previously let a caller hijack another tenant's KB by supplying its
        // id in the request body. orgId is always server-derived from auth.
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setOrgId(callerOrgId());
        return repository.save(kb);
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeBase> update(@PathVariable UUID id, @Valid @RequestBody KnowledgeBaseRequest request) {
        return repository.findByIdAndOrgId(id, callerOrgId())
                .map(existing -> {
                    existing.setName(request.name());
                    if (request.description() != null) {
                        existing.setDescription(request.description());
                    }
                    // id, orgId, createdAt remain immutable on update — controlled by JPA,
                    // not exposed on the DTO.
                    return ResponseEntity.ok(repository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable UUID id) throws Exception {
        if (!repository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        String payload = objectMapper.writeValueAsString(new KnowledgeBaseDeletionJobHandler.Payload(id.toString()));
        var job = jobQueueService.enqueue(KnowledgeBaseDeletionJobHandler.JOB_TYPE, null, payload, null, "KB_DELETE_" + id);
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    @GetMapping("/{id}/agents")
    public ResponseEntity<List<AgentSummary>> getAssignedAgents(@PathVariable UUID id) {
        if (!repository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(knowledgeBaseService.getAssignedAgents(id));
    }

    @PostMapping("/{id}/agents/{agentId}")
    public ResponseEntity<Void> assignAgent(@PathVariable UUID id, @PathVariable String agentId) {
        if (!repository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        knowledgeBaseService.assignAgentToKb(id, agentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/agents/{agentId}")
    public ResponseEntity<Void> removeAgent(@PathVariable UUID id, @PathVariable String agentId) {
        if (!repository.existsByIdAndOrgId(id, callerOrgId())) {
            return ResponseEntity.notFound().build();
        }
        knowledgeBaseService.removeAgentFromKb(id, agentId);
        return ResponseEntity.noContent().build();
    }


    /**
     * Resolves the caller's {@code orgId} from {@link AgentContextHolder}, falling back to
     * {@link TenantConstants#DEFAULT_SYSTEM_ORG} when the context is unset (e.g., system
     * background tasks). This is the same fallback pattern used by
     * {@code KnowledgeService.ingestUploadedFile} and {@code KnowledgeIngestionService}.
     */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }
}
