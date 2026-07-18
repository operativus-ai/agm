package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.model.ApprovalDTO;

/**
 * Domain Responsibility: Cross-module SPI seam for the HITL approval subsystem. Exposes
 *     ONLY the operation that {@code compute/} needs from {@code control/}: creating a
 *     pending approval row when an advisor halts a tool call. Listing / lookup / resolve
 *     are tenant-scoped and live exclusively on {@code ApprovalService} (and its REST
 *     controller) — they are NOT part of the cross-module contract.
 * State: Stateless contract.
 */
public interface ApprovalOperations {
    ApprovalDTO createApprovalRequest(String runId, String sessionId, String agentId, String toolName, String toolArguments, String message, String requestedBy, String reasoningTrace, String impactAssessment, com.operativus.agentmanager.core.model.DecisionPackage.DecisionTier tier);
}
