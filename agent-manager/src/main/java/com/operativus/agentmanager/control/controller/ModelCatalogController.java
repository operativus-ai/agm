package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.ModelCatalogResponse;
import com.operativus.agentmanager.control.service.ModelCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Admin-only REST surface for the live LLM provider catalog
 *     (cached). Lets the UI offer a dropdown of "what model ids does this provider
 *     accept right now?" instead of admins copying strings from external docs.
 *     Backs the future "Sync Catalog" affordance on the Models page.
 *
 *     Distinct from the implicit catalog use in {@link ModelCatalogService#resolveAlias}
 *     — that path is called from {@link com.operativus.agentmanager.control.service.ModelService}
 *     on save and never returns a list to the caller.
 *
 * State: Stateless.
 */
@RestController
@RequestMapping("/api/v1/models/catalog")
@PreAuthorize("hasRole('ADMIN')")
public class ModelCatalogController {

    private final ModelCatalogService catalogService;

    public ModelCatalogController(ModelCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * @summary Returns the live model id catalog for a provider, cached 10 minutes
     *     per (provider, caller orgId).
     * @logic Path variable is uppercased and dispatched to the provider-specific
     *     {@code /v1/models} fetch. Returns an empty list (NOT 404) when no
     *     ProviderCredential is configured — the caller can render an empty
     *     dropdown without special-casing the error path.
     */
    @GetMapping("/{provider}")
    public ResponseEntity<ModelCatalogResponse> getCatalog(@PathVariable String provider) {
        return ResponseEntity.ok(new ModelCatalogResponse(
                provider.toUpperCase(),
                catalogService.catalogFor(provider)
        ));
    }
}
