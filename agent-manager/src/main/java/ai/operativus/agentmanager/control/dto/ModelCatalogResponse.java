package ai.operativus.agentmanager.control.dto;

import java.util.List;

/**
 * Domain Responsibility: Outbound DTO for {@code GET /api/v1/models/catalog/{provider}} —
 *     the live model catalog returned by the provider's {@code /v1/models} endpoint,
 *     cached 10 minutes per (provider, orgId). Useful for admin tooling that wants to
 *     pre-populate a model picker without each admin having to grep curl output.
 *     Empty {@code modelIds} means either (a) no ProviderCredential is configured for
 *     the caller's org + provider, or (b) the provider call failed; the BE never throws
 *     here, so an empty list is the correct null-object.
 * State: Immutable record.
 */
public record ModelCatalogResponse(
        String provider,
        List<String> modelIds
) {}
