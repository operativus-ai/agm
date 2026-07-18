package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.compute.routing.AgentEmbeddingService;
import com.operativus.agentmanager.compute.routing.AgentEmbeddingService.BackfillSummary;
import com.operativus.agentmanager.control.dto.RoutingEmbeddingsBackfillResponse;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.TenantConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Admin surface for the routing-vector store. Eager-populates the
 *     {@code routing_vectors} table for the caller's org so the SemanticAgentScorer doesn't
 *     pay the JIT embed cost on the next dispatch. Useful after enabling
 *     {@code semantic_scoring_enabled} or after editing many agents' descriptions /
 *     capabilities. Class-level {@code hasRole('ADMIN')} per the sibling-controller pattern.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/v1/admin/routing-embeddings")
@PreAuthorize("hasRole('ADMIN')")
public class RoutingEmbeddingsAdminController {

    private final AgentEmbeddingService embeddingService;

    public RoutingEmbeddingsAdminController(AgentEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostMapping("/backfill")
    public ResponseEntity<RoutingEmbeddingsBackfillResponse> backfill() {
        String orgId = callerOrgId();
        BackfillSummary summary = embeddingService.embedAll(orgId);
        return ResponseEntity.ok(new RoutingEmbeddingsBackfillResponse(
                orgId, summary.totalAgents(), summary.embedded()));
    }

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
    }
}
