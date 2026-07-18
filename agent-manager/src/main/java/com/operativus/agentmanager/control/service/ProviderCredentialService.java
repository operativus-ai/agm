package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.ProviderCredentialRepository;
import com.operativus.agentmanager.core.entity.ProviderCredential;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain Responsibility: CRUD + lookup for per-(org, provider) LLM API keys. Implements the
 * {@link ProviderCredentialOperations} SPI consumed by compute-side providers.
 * State: Stateless service over {@link ProviderCredentialRepository}.
 */
@Service
public class ProviderCredentialService implements ProviderCredentialOperations {

    private final ProviderCredentialRepository repository;

    public ProviderCredentialService(ProviderCredentialRepository repository) {
        this.repository = repository;
    }

    /**
     * Canonical provider key. Providers are keyed uppercase everywhere they act as a lookup key
     * (the provider registry, the model catalog/test paths). Model rows, however, store mixed
     * case (e.g. {@code "OpenAI"}), so a raw compare against a stored {@code "OPENAI"} credential
     * misses. Normalize to uppercase on both read and write so an agent's {@code model.provider}
     * resolves its credential regardless of casing.
     */
    private static String canonical(String provider) {
        return provider.toUpperCase(Locale.ROOT);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveDefaultKey(String orgId, String provider) {
        if (orgId == null || orgId.isBlank() || provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return repository.findByOrgIdAndProvider(orgId, canonical(provider))
                .map(ProviderCredential::getApiKey)
                .filter(k -> k != null && !k.isBlank());
    }

    @Transactional(readOnly = true)
    public List<ProviderCredential> listForOrg(String orgId) {
        Objects.requireNonNull(orgId, "orgId");
        return repository.findByOrgIdOrderByProvider(orgId);
    }

    @Transactional(readOnly = true)
    public Optional<ProviderCredential> findById(String id) {
        return repository.findById(id);
    }

    @Transactional
    public ProviderCredential upsert(String orgId, String provider, String apiKey, String label) {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        String canonicalProvider = canonical(provider);
        ProviderCredential row = repository.findByOrgIdAndProvider(orgId, canonicalProvider).orElseGet(() -> {
            ProviderCredential fresh = new ProviderCredential();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setOrgId(orgId);
            fresh.setProvider(canonicalProvider);
            return fresh;
        });
        row.setApiKey(apiKey);
        row.setLabel(label);
        return repository.save(row);
    }

    /**
     * Edit an existing (org, provider) credential. A blank/null {@code apiKey} means
     * "keep the currently stored key" — the server never returns the plaintext key on
     * read, so requiring a re-type just to change the label would be hostile. Passing a
     * non-blank key rotates it. The label is always overwritten with the supplied value.
     */
    @Transactional
    public ProviderCredential update(String orgId, String provider, String apiKey, String label) {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(provider, "provider");
        ProviderCredential row = repository.findByOrgIdAndProvider(orgId, canonical(provider))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No credential found for provider '" + provider + "' to update"));
        if (apiKey != null && !apiKey.isBlank()) {
            row.setApiKey(apiKey);
        }
        row.setLabel(label);
        return repository.save(row);
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }
}
