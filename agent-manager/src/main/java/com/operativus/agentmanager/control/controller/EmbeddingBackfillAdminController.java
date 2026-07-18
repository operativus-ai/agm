package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.EmbeddingBackfillResponse;
import com.operativus.agentmanager.control.service.EmbeddingBackfillService;
import com.operativus.agentmanager.control.service.EmbeddingBackfillService.BackfillResult;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.TenantConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Admin surface for re-embedding the pgvector store. Re-embeds the caller
 *   org's {@code vector_store} rows under the currently-elected embedding model — the operational
 *   step after pointing {@code DEFAULT_MODEL_EMBEDDING} at a real model (so the chunks embedded by
 *   the previous/NoOp model are rebuilt without re-uploading documents). Class-level
 *   {@code hasRole('ADMIN')} per the sibling-controller pattern (cf. {@code RoutingEmbeddingsAdminController}).
 * State: Stateless.
 */
@RestController
@RequestMapping("/api/v1/admin/embeddings")
@PreAuthorize("hasRole('ADMIN')")
public class EmbeddingBackfillAdminController {

    private final EmbeddingBackfillService backfillService;

    public EmbeddingBackfillAdminController(EmbeddingBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    /**
     * Re-embeds the caller org's vectors. Optional {@code storeType} ({@code KB} / {@code MEMORY})
     * narrows the scope; omitted re-embeds all of the org's rows. Refuses (400) when the active model
     * is non-functional rather than corrupt the store — see {@link EmbeddingBackfillService}.
     */
    @PostMapping("/backfill")
    public ResponseEntity<EmbeddingBackfillResponse> backfill(
            @RequestParam(name = "storeType", required = false) @Nullable String storeType) {
        BackfillResult r = backfillService.reembedForOrg(callerOrgId(), storeType);
        return ResponseEntity.ok(new EmbeddingBackfillResponse(
                r.orgId(), r.storeTypeFilter(), r.scanned(), r.reembedded(), r.dimensions(), r.byStoreType()));
    }

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
    }
}
