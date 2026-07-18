package ai.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Domain Responsibility: Resolves human-friendly LLM model aliases (e.g.
 *     {@code claude-haiku-4-5}) to the latest dated snapshot (e.g.
 *     {@code claude-haiku-4-5-20251001}) by calling each provider's live
 *     {@code /v1/models} endpoint. Results are cached per (provider, orgId)
 *     for 10 minutes to keep boot-time and admin-form-save costs negligible.
 *
 *     This service is what makes the alias-as-input UX work: admins type the
 *     alias they remember from the docs; the BE substitutes the latest dated
 *     identifier before persisting so production behavior stays deterministic
 *     until the admin explicitly refreshes.
 *
 * State: Stateful (Caffeine cache); thread-safe.
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

    /** Catalog responses change on the order of days; 10-minute TTL is plenty
     *  to absorb a burst of admin saves while keeping the refresh window short
     *  enough that a newly-released snapshot becomes available without restart. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final String DEFAULT_SYSTEM_ORG = "DEFAULT_SYSTEM_ORG";

    /** Anthropic dated snapshots end with {@code -YYYYMMDD}. */
    private static final Pattern ANTHROPIC_DATED = Pattern.compile(".*-\\d{8}$");

    /** OpenAI dated snapshots end with {@code -YYYY-MM-DD}. */
    private static final Pattern OPENAI_DATED = Pattern.compile(".*-\\d{4}-\\d{2}-\\d{2}$");

    private final ProviderCredentialOperations credentials;
    private final Cache<CacheKey, List<String>> catalogCache;
    private final RestClient http;
    private final ObjectMapper mapper;

    public ModelCatalogService(ProviderCredentialOperations credentials) {
        this.credentials = credentials;
        this.catalogCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(64)
                .build();
        this.http = RestClient.builder()
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * @summary Resolves a model name to its canonical form for storage. When the input is an
     *     alias and the provider supports dated snapshots, returns the latest dated id from
     *     the live catalog. When the input is already dated, returned as-is. When the lookup
     *     fails (no credential, provider unreachable, etc.), returns the input unchanged with
     *     a WARN — never blocks the save.
     */
    public String resolveAlias(String provider, String modelName) {
        if (provider == null || modelName == null || modelName.isBlank()) return modelName;
        String upper = provider.toUpperCase();
        if (alreadyDated(upper, modelName)) {
            return modelName;
        }
        try {
            List<String> catalog = catalogFor(upper);
            String resolved = pickLatestDatedSnapshot(upper, modelName, catalog);
            if (!resolved.equals(modelName)) {
                log.info("Resolved alias '{}' → '{}' from {} live catalog", modelName, resolved, upper);
            }
            return resolved;
        } catch (Exception e) {
            log.warn("Catalog resolve failed for ({}, '{}'): {} — keeping alias as-is", upper, modelName, e.getMessage());
            return modelName;
        }
    }

    /**
     * @summary Lists the live model catalog for a provider, cached per (provider, callerOrgId).
     * @logic Resolves the API key via {@link ProviderCredentialOperations} scoped strictly
     *     to the caller's org (or DEFAULT_SYSTEM_ORG when there is no caller context, e.g.
     *     bootstrap-time alias resolution). Returns an empty list when the caller's org has
     *     no ProviderCredential — never falls back to another org's key. Cache entries live
     *     10 minutes.
     */
    public List<String> catalogFor(String provider) {
        String upper = provider.toUpperCase();
        String orgId = AgentContextHolder.getOrgId();
        if (orgId == null || orgId.isBlank()) orgId = DEFAULT_SYSTEM_ORG;
        return catalogCache.get(new CacheKey(upper, orgId), this::fetchFromProvider);
    }

    private List<String> fetchFromProvider(CacheKey key) {
        Optional<String> apiKey = credentials.resolveDefaultKey(key.orgId(), key.provider());
        if (apiKey.isEmpty()) {
            log.debug("No ProviderCredential configured for {} (org={}); skipping catalog fetch", key.provider(), key.orgId());
            return List.of();
        }
        return switch (key.provider()) {
            case "ANTHROPIC" -> fetchAnthropicCatalog(apiKey.get());
            case "OPENAI" -> fetchOpenAiCatalog(apiKey.get());
            case "GOOGLE" -> fetchGoogleCatalog(apiKey.get());
            default -> List.of();
        };
    }

    private List<String> fetchAnthropicCatalog(String apiKey) {
        String body = http.get()
                .uri("https://api.anthropic.com/v1/models")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .retrieve()
                .body(String.class);
        return parseDataArray(body, "data", "id");
    }

    private List<String> fetchOpenAiCatalog(String apiKey) {
        String body = http.get()
                .uri("https://api.openai.com/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .retrieve()
                .body(String.class);
        return parseDataArray(body, "data", "id");
    }

    private List<String> fetchGoogleCatalog(String apiKey) {
        String body = http.get()
                .uri("https://generativelanguage.googleapis.com/v1beta/models?key={key}", apiKey)
                .retrieve()
                .body(String.class);
        List<String> raw = parseDataArray(body, "models", "name");
        // Google returns ids prefixed with "models/" — strip it so callers can compare
        // to ModelEntity.model_name values without surprise.
        List<String> stripped = new ArrayList<>(raw.size());
        for (String id : raw) {
            stripped.add(id.startsWith("models/") ? id.substring("models/".length()) : id);
        }
        return stripped;
    }

    private List<String> parseDataArray(String body, String arrayField, String idField) {
        if (body == null) return List.of();
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode array = root.path(arrayField);
            if (!array.isArray()) return List.of();
            List<String> ids = new ArrayList<>(array.size());
            for (JsonNode entry : array) {
                JsonNode id = entry.path(idField);
                if (id.isTextual()) ids.add(id.asText());
            }
            return Collections.unmodifiableList(ids);
        } catch (Exception e) {
            log.warn("Failed to parse catalog body: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean alreadyDated(String provider, String modelName) {
        return switch (provider) {
            case "ANTHROPIC" -> ANTHROPIC_DATED.matcher(modelName).matches();
            case "OPENAI" -> OPENAI_DATED.matcher(modelName).matches();
            default -> false;
        };
    }

    private String pickLatestDatedSnapshot(String provider, String alias, List<String> catalog) {
        if (catalog == null || catalog.isEmpty()) return alias;
        Pattern dateSuffix = switch (provider) {
            case "ANTHROPIC" -> Pattern.compile("^" + Pattern.quote(alias) + "-\\d{8}$");
            case "OPENAI" -> Pattern.compile("^" + Pattern.quote(alias) + "-\\d{4}-\\d{2}-\\d{2}$");
            default -> null;
        };
        if (dateSuffix == null) return alias;
        return catalog.stream()
                .filter(id -> dateSuffix.matcher(id).matches())
                .max(Comparator.naturalOrder())
                .orElse(alias);
    }

    /** Invalidate cached catalogs — useful after rotating a ProviderCredential. */
    public void invalidateCache() {
        catalogCache.invalidateAll();
    }

    private record CacheKey(String provider, String orgId) {}
}
