package ai.operativus.agentmanager.control.dto.composio;

import java.util.List;

/**
 * Domain Responsibility: Wire-format response for
 *   {@code POST /api/admin/composio/catalog/import}. Carries per-action outcomes
 *   so operators can see exactly which actions landed, which were skipped, and
 *   which failed.
 *
 *   <p>The {@code skippedExisting} and {@code created} lists are bounded by
 *   {@code totalFetched} — if the upstream catalog had 200 actions, the lists
 *   sum to 200. {@code failures} is a parallel list of action-name → error-
 *   message pairs for any per-action exception encountered during the bulk
 *   insert (typically validation failures from a malformed Composio name).
 *
 * State: Stateless (Immutable Record carrier)
 */
public record ComposioCatalogImportResponse(
        String app,
        int totalFetched,
        List<String> created,
        List<String> skippedExisting,
        List<ImportFailure> failures
) {
    public record ImportFailure(String actionName, String reason) {}
}
