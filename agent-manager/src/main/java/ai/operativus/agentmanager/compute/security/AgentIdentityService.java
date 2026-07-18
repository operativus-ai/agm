package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.control.repository.AgentCredentialRepository;
import ai.operativus.agentmanager.core.entity.AgentCredential;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.security.SsrfGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Manages first-class agent identity — credential lifecycle, JIT token minting,
 * scope validation, and credential resolution for tool execution contexts.
 *
 * <p>Architecture: This service owns all agent credential operations. It caches minted tokens
 * with TTL slightly below their expiry to minimize latency on repeated tool calls.</p>
 *
 * State: Stateful (token cache)
 */
@Service
public class AgentIdentityService {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentityService.class);

    private final AgentCredentialRepository credentialRepository;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public AgentIdentityService(AgentCredentialRepository credentialRepository) {
        this.credentialRepository = credentialRepository;
    }

    /**
     * Resolves the active credential for a given agent and provider.
     * Returns the first enabled credential matching the agent and provider.
     */
    public Optional<AgentCredential> resolveCredential(String agentId, String providerName) {
        return credentialRepository.findByAgentIdAndProviderName(agentId, providerName)
                .stream()
                .filter(AgentCredential::isEnabled)
                .findFirst();
    }

    /**
     * Returns all enabled credentials for an agent, keyed by provider name.
     */
    public Map<String, AgentCredential> resolveAllCredentials(String agentId) {
        Map<String, AgentCredential> result = new LinkedHashMap<>();
        for (AgentCredential cred : credentialRepository.findByAgentIdAndEnabledTrue(agentId)) {
            result.putIfAbsent(cred.getProviderName(), cred);
        }
        return result;
    }

    /**
     * Mints or retrieves a cached JIT token for the given credential.
     * For API_KEY/BEARER types, returns the stored secret directly.
     * For OAUTH2 types, performs client_credentials flow against the token endpoint.
     */
    public String mintToken(AgentCredential credential) {
        String cacheKey = credential.getId();

        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Using cached token for credential '{}' (provider: {})", credential.getId(), credential.getProviderName());
            return cached.token;
        }

        String token;
        LocalDateTime expiresAt;

        switch (credential.getCredentialType().toUpperCase()) {
            case "API_KEY", "BEARER" -> {
                token = credential.getEncryptedSecret();
                expiresAt = credential.getExpiresAt() != null ? credential.getExpiresAt() : LocalDateTime.now().plusHours(24);
            }
            case "OAUTH2" -> {
                token = performOAuth2ClientCredentialsFlow(credential);
                expiresAt = LocalDateTime.now().plusMinutes(55); // Standard OAuth2 token TTL minus buffer
            }
            default -> throw new BusinessValidationException(
                    "Unsupported credential type: " + credential.getCredentialType());
        }

        // Cache with TTL slightly before actual expiry
        tokenCache.put(cacheKey, new CachedToken(token, expiresAt.minusMinutes(2)));
        log.info("Minted token for credential '{}' (agent: {}, provider: {}, type: {})",
                credential.getId(), credential.getAgentId(), credential.getProviderName(), credential.getCredentialType());

        return token;
    }

    /**
     * Validates that the credential's scopes include the requested scope.
     */
    public boolean validateScope(AgentCredential credential, String requiredScope) {
        if (credential.getScopes() == null || credential.getScopes().isBlank()) {
            return true; // No scope restriction = all scopes allowed
        }
        Set<String> grantedScopes = Set.of(credential.getScopes().split(","));
        return grantedScopes.contains(requiredScope.trim());
    }

    /**
     * Evicts the cached token for a credential (e.g., after a 401 response).
     */
    public void evictCachedToken(String credentialId) {
        tokenCache.remove(credentialId);
        log.debug("Evicted cached token for credential '{}'", credentialId);
    }

    // --- CRUD operations delegated to repository ---

    public List<AgentCredential> getCredentials(String agentId) {
        return credentialRepository.findByAgentId(agentId);
    }

    public AgentCredential getCredential(String id, String agentId) {
        return credentialRepository.findByIdAndAgentId(id, agentId)
                .orElseThrow(() -> new ResourceNotFoundException("AgentCredential", id));
    }

    public AgentCredential createCredential(AgentCredential credential) {
        if (credential.getId() == null || credential.getId().isBlank()) {
            credential.setId(UUID.randomUUID().toString());
        }
        rejectSsrfTokenEndpointIfPresent(credential);
        log.info("Creating agent credential for agent '{}', provider '{}'", credential.getAgentId(), credential.getProviderName());
        return credentialRepository.save(credential);
    }

    public AgentCredential updateCredential(String id, String agentId, AgentCredential update) {
        AgentCredential existing = getCredential(id, agentId);
        rejectSsrfTokenEndpointIfPresent(update);
        existing.setCredentialType(update.getCredentialType());
        existing.setProviderName(update.getProviderName());
        existing.setEncryptedSecret(update.getEncryptedSecret());
        existing.setScopes(update.getScopes());
        existing.setTokenEndpoint(update.getTokenEndpoint());
        existing.setClientId(update.getClientId());
        existing.setExpiresAt(update.getExpiresAt());
        existing.setEnabled(update.isEnabled());
        evictCachedToken(id);
        return credentialRepository.save(existing);
    }

    // Write-time SSRF guard: reject a tokenEndpoint that points at loopback / RFC-1918 /
    // 169.254 cloud-metadata / non-http(s) schemes. Defense-in-depth alongside the runtime
    // check in performOAuth2ClientCredentialsFlow — write-time rejection gives the operator
    // immediate 400 feedback instead of a silent mint-time failure later under live traffic.
    private void rejectSsrfTokenEndpointIfPresent(AgentCredential credential) {
        String tokenEndpoint = credential.getTokenEndpoint();
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            return;
        }
        String reason = SsrfGuard.validate(tokenEndpoint, false);
        if (reason != null) {
            throw new BusinessValidationException(
                    "OAuth2 tokenEndpoint rejected by SSRF guard: " + reason);
        }
    }

    public void deleteCredential(String id, String agentId) {
        if (credentialRepository.findByIdAndAgentId(id, agentId).isEmpty()) {
            throw new ResourceNotFoundException("AgentCredential", id);
        }
        evictCachedToken(id);
        credentialRepository.deleteById(id);
        log.info("Deleted agent credential '{}'", id);
    }

    /**
     * Performs OAuth2 client_credentials grant against the credential's token endpoint.
     */
    private String performOAuth2ClientCredentialsFlow(AgentCredential credential) {
        if (credential.getTokenEndpoint() == null || credential.getTokenEndpoint().isBlank()) {
            throw new BusinessValidationException(
                    "OAuth2 credential '" + credential.getId() + "' has no token endpoint configured");
        }
        if (credential.getClientId() == null || credential.getClientId().isBlank()) {
            throw new BusinessValidationException(
                    "OAuth2 credential '" + credential.getId() + "' has no client_id configured");
        }
        // Runtime SSRF backstop. Write-time validation in createCredential / updateCredential
        // rejects bad URLs at admission, but a credential row already persisted before this
        // guard existed, or one written via a path that bypasses the service, would still
        // reach here. This unconditional check ensures the POST never goes out against
        // loopback / RFC-1918 / 169.254 cloud-metadata / non-http(s) schemes.
        String ssrfReason = SsrfGuard.validate(credential.getTokenEndpoint(), false);
        if (ssrfReason != null) {
            log.warn("OAuth2 token mint rejected for credential '{}' — SSRF guard: {} (tokenEndpoint={})",
                    credential.getId(), ssrfReason, credential.getTokenEndpoint());
            throw new BusinessValidationException(
                    "OAuth2 tokenEndpoint rejected by SSRF guard: " + ssrfReason);
        }

        try {
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
            String body = "grant_type=client_credentials"
                    + "&client_id=" + java.net.URLEncoder.encode(credential.getClientId(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&client_secret=" + java.net.URLEncoder.encode(credential.getEncryptedSecret(), java.nio.charset.StandardCharsets.UTF_8);

            if (credential.getScopes() != null && !credential.getScopes().isBlank()) {
                body += "&scope=" + java.net.URLEncoder.encode(credential.getScopes().replace(",", " "), java.nio.charset.StandardCharsets.UTF_8);
            }

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(credential.getTokenEndpoint()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BusinessValidationException(
                        "OAuth2 token request failed for credential '" + credential.getId()
                                + "': HTTP " + response.statusCode());
            }

            // Extract access_token from JSON response
            String responseBody = response.body();
            int tokenStart = responseBody.indexOf("\"access_token\"");
            if (tokenStart == -1) {
                throw new BusinessValidationException("OAuth2 response missing access_token for credential '" + credential.getId() + "'");
            }
            // Simple extraction — find the value after "access_token":"
            int valueStart = responseBody.indexOf("\"", responseBody.indexOf(":", tokenStart) + 1) + 1;
            int valueEnd = responseBody.indexOf("\"", valueStart);
            return responseBody.substring(valueStart, valueEnd);

        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessValidationException(
                    "OAuth2 client_credentials flow failed for credential '" + credential.getId() + "': " + e.getMessage());
        }
    }

    private record CachedToken(String token, LocalDateTime expiresAt) {
        boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }
    }
}
