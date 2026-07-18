package com.operativus.agentmanager.control.dto;

import com.operativus.agentmanager.core.model.RunOptions;

import java.util.List;

/**
 * Domain Responsibility: Inbound payload for {@code POST /api/runs} — the universal-dispatch
 *     entry point that resolves a target {@code agentId} dynamically. {@code orgId} and
 *     {@code userId} are intentionally NOT in this record (they come from the JWT only)
 *     so a caller cannot spoof attribution by setting them in the body — the same
 *     §28 RBAC pattern that {@code AgentsController.resolveCallerOrgId} enforces.
 *
 *     <p>DR-FR-5: {@code media} is an optional list of inputs (images, audio, etc.) passed
 *     through to the resolved agent's runtime. {@code type} is a MIME-type string,
 *     {@code data} is a URL (when starting with "http") or base64-encoded bytes.
 * State: Stateless (Data Transfer Object)
 */
public record DispatchRunRequest(
        String message,
        String sessionId,
        Boolean generateFollowups,
        RunOptions options,
        List<MediaInput> media
) {
    public record MediaInput(String type, String data) {}
}
