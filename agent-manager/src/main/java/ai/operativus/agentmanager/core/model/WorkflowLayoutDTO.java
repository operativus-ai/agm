package ai.operativus.agentmanager.core.model;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Wire shape for a workflow's saved DAG-editor node layout (REQ-DR-5) — used by both
 * {@code GET /workflows/{id}/layout} (response) and {@code PUT} (full-replace request body).
 * An empty {@code positions} list means "no saved layout" (the editor falls back to ELK).
 */
public record WorkflowLayoutDTO(@Valid List<NodePosition> positions) {

    public static WorkflowLayoutDTO empty() {
        return new WorkflowLayoutDTO(List.of());
    }
}
