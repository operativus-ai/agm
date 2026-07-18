package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCatalogServiceTest {

    @Mock private ProviderCredentialOperations credentials;

    @Test
    void catalogFor_callerOrgHasNoCredential_returnsEmptyAndDoesNotFallBackToSystemOrg() {
        // Security regression: prior to the fix, missing org cred would fall back
        // to DEFAULT_SYSTEM_ORG, leaking the system tenant's catalog across orgs.
        when(credentials.resolveDefaultKey(eq("org-tenant-a"), eq("ANTHROPIC")))
                .thenReturn(Optional.empty());
        ModelCatalogService service = new ModelCatalogService(credentials);

        List<String> result = ScopedValue.where(AgentContextHolder.orgId, "org-tenant-a")
                .call(() -> service.catalogFor("anthropic"));

        assertTrue(result.isEmpty(),
                "Caller-org with no credential must get an empty catalog — never another org's keys");
        verify(credentials, times(1)).resolveDefaultKey("org-tenant-a", "ANTHROPIC");
        verify(credentials, never()).resolveDefaultKey(eq("DEFAULT_SYSTEM_ORG"), any());
    }

    @Test
    void catalogFor_noAgentContextOrgId_resolvesAgainstSystemOrg() {
        // Bootstrap-time alias resolution path: no caller context bound, defaults
        // to DEFAULT_SYSTEM_ORG. This is allowed because the absence of an
        // authenticated principal proves there's no cross-tenant exposure risk.
        when(credentials.resolveDefaultKey(eq("DEFAULT_SYSTEM_ORG"), eq("OPENAI")))
                .thenReturn(Optional.empty());
        ModelCatalogService service = new ModelCatalogService(credentials);

        List<String> result = service.catalogFor("openai");

        assertTrue(result.isEmpty(), "Empty optional from credentials maps to empty catalog");
        verify(credentials, times(1)).resolveDefaultKey("DEFAULT_SYSTEM_ORG", "OPENAI");
    }

    @Test
    void resolveAlias_alreadyDatedAnthropic_returnsInputUnchanged() {
        ModelCatalogService service = new ModelCatalogService(credentials);

        String resolved = service.resolveAlias("ANTHROPIC", "claude-sonnet-4-6-20251001");

        assertEquals("claude-sonnet-4-6-20251001", resolved,
                "Already-dated snapshot must be a no-op — never trigger a provider call");
        verify(credentials, never()).resolveDefaultKey(any(), any());
    }

    @Test
    void resolveAlias_blankInput_returnsAsIs() {
        ModelCatalogService service = new ModelCatalogService(credentials);
        assertEquals("", service.resolveAlias("ANTHROPIC", ""));
        assertEquals(null, service.resolveAlias("ANTHROPIC", null));
        verify(credentials, never()).resolveDefaultKey(any(), any());
    }
}
