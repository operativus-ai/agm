package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.ProviderCredentialRepository;
import ai.operativus.agentmanager.core.entity.ProviderCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pin the edit contract of {@link ProviderCredentialService#update}.
 * A blank/null key means "keep the stored key" (the server never returns the plaintext, so
 * editing a label must not force a re-type); a non-blank key rotates it; the label is always
 * overwritten; and editing a provider that has no row is an {@link IllegalArgumentException}.
 *
 * State: Stateless — pure Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ProviderCredentialServiceUpdateTest {

    private static final String ORG = "org-1";

    @Mock private ProviderCredentialRepository repository;

    private ProviderCredential existingRow() {
        ProviderCredential row = new ProviderCredential();
        row.setId("cred-1");
        row.setOrgId(ORG);
        row.setProvider("OPENAI");
        row.setApiKey("sk-existing");
        row.setLabel("Old label");
        return row;
    }

    @Test
    void blankKeyKeepsExistingKeyAndUpdatesLabel() {
        ProviderCredential row = existingRow();
        when(repository.findByOrgIdAndProvider(ORG, "OPENAI")).thenReturn(Optional.of(row));
        when(repository.save(any(ProviderCredential.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderCredentialService service = new ProviderCredentialService(repository);
        ProviderCredential saved = service.update(ORG, "OPENAI", "   ", "New label");

        assertEquals("sk-existing", saved.getApiKey(), "blank key must leave the stored key untouched");
        assertEquals("New label", saved.getLabel(), "label must be overwritten");
    }

    @Test
    void nonBlankKeyRotatesTheKey() {
        ProviderCredential row = existingRow();
        when(repository.findByOrgIdAndProvider(ORG, "OPENAI")).thenReturn(Optional.of(row));
        when(repository.save(any(ProviderCredential.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderCredentialService service = new ProviderCredentialService(repository);
        ProviderCredential saved = service.update(ORG, "OPENAI", "sk-rotated", "New label");

        assertEquals("sk-rotated", saved.getApiKey(), "a non-blank key must rotate the stored value");
        assertEquals("New label", saved.getLabel());
    }

    @Test
    void updatingAProviderWithNoRowThrows() {
        when(repository.findByOrgIdAndProvider(ORG, "OPENAI")).thenReturn(Optional.empty());

        ProviderCredentialService service = new ProviderCredentialService(repository);
        assertThrows(IllegalArgumentException.class,
                () -> service.update(ORG, "OPENAI", "sk-x", "label"),
                "editing a provider that has no stored credential must fail loudly");
    }

    @Test
    void resolveDefaultKeyIsCaseInsensitiveOnProvider() {
        // Model rows store mixed case ("OpenAI"); credentials store uppercase ("OPENAI").
        // resolveDefaultKey must canonicalize so an agent run resolves its key regardless of casing.
        ProviderCredential row = existingRow();
        when(repository.findByOrgIdAndProvider(ORG, "OPENAI")).thenReturn(Optional.of(row));

        ProviderCredentialService service = new ProviderCredentialService(repository);

        assertEquals(Optional.of("sk-existing"), service.resolveDefaultKey(ORG, "OpenAI"),
                "mixed-case model provider must resolve the uppercase-stored credential");
        assertEquals(Optional.of("sk-existing"), service.resolveDefaultKey(ORG, "openai"),
                "lowercase provider must also resolve");
    }
}
