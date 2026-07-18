package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.compute.tools.composio.ComposioCatalogClient;
import ai.operativus.agentmanager.control.dto.composio.ComposioCatalogImportRequest;
import ai.operativus.agentmanager.control.dto.composio.ComposioCatalogImportResponse;
import ai.operativus.agentmanager.control.dto.composio.ComposioCatalogListResponse;
import ai.operativus.agentmanager.control.security.CallerContext;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.control.service.ComposioConfigService;
import ai.operativus.agentmanager.core.model.ComposioCatalogAction;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Gap #21 — operator-facing surface for browsing the
 *   upstream Composio catalog and bulk-importing apps' actions into the
 *   {@code composio_action_config} allowlist.
 *
 *   <p>Separate from {@link ComposioAdminController} (per-row CRUD) because
 *   the catalog reads <em>upstream</em> Composio rather than the AGM DB —
 *   different failure modes (network), different rate-limit posture (external
 *   API), different operator mental model ("discover" vs "manage").
 *
 *   <p>Both endpoints are SUPER_ADMIN-gated — action config is global, so
 *   the same gate as {@link ComposioAdminController}. Tagged in
 *   {@code SuperAdminEndpointCoverageArchTest} with the focused authz test
 *   {@code ComposioCatalogAdminAuthzRuntimeTest}.
 *
 * State: Stateless.
 */
@RestController
@RequestMapping("/api/admin/composio/catalog")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ComposioCatalogController {

    private final ComposioCatalogClient catalogClient;
    private final ComposioConfigService configService;

    public ComposioCatalogController(ComposioCatalogClient catalogClient,
                                     ComposioConfigService configService) {
        this.catalogClient = catalogClient;
        this.configService = configService;
    }

    /**
     * Lists upstream Composio actions, optionally filtered by {@code app}.
     * Returns an empty list (not an error) when the Composio API key is unset
     * or upstream is unreachable — operators see the empty state and check
     * the logs for the WARN line from {@link ComposioCatalogClient}.
     */
    @GetMapping
    public ResponseEntity<ComposioCatalogListResponse> listCatalog(
            @RequestParam(value = "app", required = false) String app,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<ComposioCatalogAction> items = catalogClient.listActions(app, limit);
        return ResponseEntity.ok(new ComposioCatalogListResponse(items, items.size(), app));
    }

    /**
     * Bulk-imports every action under {@code request.app()} from the upstream
     * catalog into {@code composio_action_config}. Idempotent — existing rows
     * are skipped unless {@code overwriteExisting=true}.
     */
    @PostMapping("/import")
    public ResponseEntity<ComposioCatalogImportResponse> importApp(
            @Valid @RequestBody ComposioCatalogImportRequest request) {
        List<ComposioCatalogAction> upstream = catalogClient.listActions(request.app(), null);
        List<String> actionNames = upstream.stream()
                .map(ComposioCatalogAction::name)
                .collect(Collectors.toList());

        boolean overwrite = Boolean.TRUE.equals(request.overwriteExisting());
        ComposioConfigService.BulkImportResult result = configService.bulkImportActions(
                actionNames, overwrite, request.defaultTier(),
                callerOrgId(), callerUsername());

        List<ComposioCatalogImportResponse.ImportFailure> failures = result.failures().stream()
                .map(e -> new ComposioCatalogImportResponse.ImportFailure(e.getKey(), e.getValue()))
                .toList();
        return ResponseEntity.ok(new ComposioCatalogImportResponse(
                request.app(),
                upstream.size(),
                result.created(),
                result.skipped(),
                failures));
    }

    private String callerOrgId() {
        return CallerContext.resolveCallerOrgId();
    }

    private String callerUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getUsername();
        }
        return "system";
    }
}
