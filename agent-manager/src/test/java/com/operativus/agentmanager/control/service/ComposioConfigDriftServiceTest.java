package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.compute.tools.composio.ComposioActionRegistry;
import com.operativus.agentmanager.control.dto.composio.ConfigDriftResponse;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import com.operativus.agentmanager.control.repository.ComposioConnectionConfigRepository;
import com.operativus.agentmanager.core.entity.ComposioActionConfig;
import com.operativus.agentmanager.core.entity.ComposioConnectionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins {@link ComposioConfigDriftService#buildDriftSnapshot()}.
 *   This service powers the admin {@code /api/admin/composio/config-drift} endpoint
 *   used by ops to detect misalignment between the in-memory action registry and the
 *   DB-backed config tables. Silent breakage here means an admin loads a "looks fine"
 *   drift report while actual drift goes unflagged — operationally invisible.
 *
 *   <p>The service has four moving parts pinned here:
 *   <ul>
 *     <li><b>Action set classification</b>: every live-registry action is bucketed
 *         into {@code inRegistryNotInDb}, {@code inSync}, or (implicitly) the DB-only
 *         "disabled" list. Drift = the first bucket is non-empty.</li>
 *     <li><b>Case normalization</b>: DB action names are uppercased before comparison
 *         to the registry (which already uppercases). A lowercase DB entry must match
 *         an uppercase registry entry.</li>
 *     <li><b>Registry source attribution</b>: when no DB actions are enabled, source
 *         is reported as {@code PROPERTIES_FALLBACK} (so ops knows the registry came
 *         from {@code application.properties} not from DB).</li>
 *     <li><b>Connection coverage gap</b>: orgs that have agents but no Composio
 *         connection — surfaces orgs that need onboarding.</li>
 *   </ul>
 *
 *   <p>The {@code buildDriftSnapshot} method has a 63-line body; this test class
 *   exercises each output field independently rather than asserting one giant
 *   composite response.
 *
 * State: Stateless (per-test mocks via {@link MockitoExtension}).
 */
@ExtendWith(MockitoExtension.class)
public class ComposioConfigDriftServiceTest {

    private final ComposioActionConfigRepository actionRepo = mock(ComposioActionConfigRepository.class);
    private final ComposioConnectionConfigRepository connectionRepo = mock(ComposioConnectionConfigRepository.class);
    private final AgentRepository agentRepo = mock(AgentRepository.class);
    private final ComposioActionRegistry actionRegistry = mock(ComposioActionRegistry.class);

    private final ComposioConfigDriftService service = new ComposioConfigDriftService(
            actionRepo, connectionRepo, agentRepo, actionRegistry);

    // ════════════════════════════════════════════════════════════════
    // registrySource attribution
    // ════════════════════════════════════════════════════════════════

    @Test
    void registrySource_isPropertiesFallback_whenNoDbActionsAreEnabled() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("GMAIL_SEND"));
        when(actionRegistry.wasTruncated()).thenReturn(false);

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals("PROPERTIES_FALLBACK", resp.registrySource(),
                "empty DB → registry source must surface as PROPERTIES_FALLBACK so ops knows config came from properties");
    }

    @Test
    void registrySource_isPropertiesFallback_whenAllDbActionsAreDisabled() {
        // Empty enabled-DB set forces PROPERTIES_FALLBACK even if rows exist.
        ComposioActionConfig disabled = action("gmail_send", false);
        when(actionRepo.findAll()).thenReturn(List.of(disabled));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("GMAIL_SEND"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals("PROPERTIES_FALLBACK", resp.registrySource(),
                "all-disabled DB rows must still trip PROPERTIES_FALLBACK attribution — dbEnabledNames is empty");
    }

    @Test
    void registrySource_isDb_whenAtLeastOneDbActionIsEnabled() {
        when(actionRepo.findAll()).thenReturn(List.of(action("gmail_send", true)));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("GMAIL_SEND"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals("DB", resp.registrySource(),
                "any enabled DB row must attribute source to DB");
    }

    // ════════════════════════════════════════════════════════════════
    // action drift — inRegistryNotInDb
    // ════════════════════════════════════════════════════════════════

    @Test
    void actionDrift_registryHasActionNotInDb_appearsInInRegistryNotInDb() {
        when(actionRepo.findAll()).thenReturn(List.of(action("gmail_send", true)));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        // SLACK_POST exists in registry but NOT in DB → drift signal.
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("GMAIL_SEND", "SLACK_POST"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals(List.of("SLACK_POST"), resp.actionDrift().inRegistryNotInDb(),
                "registry-only action must surface in inRegistryNotInDb — this is the primary drift signal");
        assertEquals(List.of("GMAIL_SEND"), resp.actionDrift().inSync(),
                "DB-and-registry overlap must appear in inSync");
    }

    @Test
    void actionDrift_inRegistryNotInDb_isSortedAlphabetically() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("ZEBRA_ACTION", "ALPHA_ACTION", "MIKE_ACTION"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals(List.of("ALPHA_ACTION", "MIKE_ACTION", "ZEBRA_ACTION"),
                resp.actionDrift().inRegistryNotInDb(),
                "stable alphabetical sort — drift report would be hard to diff otherwise");
    }

    // ════════════════════════════════════════════════════════════════
    // action drift — inSync (case-insensitive match)
    // ════════════════════════════════════════════════════════════════

    @Test
    void actionDrift_lowercaseDbActionMatchesUppercaseRegistry_appearsInInSync() {
        // DB stores names in any case; service uppercases before comparing.
        // Without that normalization, "gmail_send" (DB) vs "GMAIL_SEND" (registry)
        // would falsely look like drift.
        when(actionRepo.findAll()).thenReturn(List.of(action("gmail_send", true)));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("GMAIL_SEND"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals(List.of("GMAIL_SEND"), resp.actionDrift().inSync(),
                "case-insensitive match prevents false-positive drift");
        assertTrue(resp.actionDrift().inRegistryNotInDb().isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    // action drift — inDbDisabled
    // ════════════════════════════════════════════════════════════════

    @Test
    void actionDrift_dbDisabledActions_appearInInDbDisabled() {
        ComposioActionConfig enabled = action("gmail_send", true);
        ComposioActionConfig disabled1 = action("slack_post", false);
        ComposioActionConfig disabled2 = action("trello_create", false);
        when(actionRepo.findAll()).thenReturn(List.of(enabled, disabled1, disabled2));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("GMAIL_SEND"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        // inDbDisabled preserves the raw (not uppercased) action name, sorted alphabetically.
        assertEquals(List.of("slack_post", "trello_create"), resp.actionDrift().inDbDisabled(),
                "DB-disabled actions must appear in inDbDisabled (raw case), sorted");
        assertEquals(3, resp.actionDrift().totalInDb(),
                "totalInDb counts all rows regardless of enabled flag");
        assertEquals(1, resp.actionDrift().enabledInDb(),
                "enabledInDb counts only enabled rows");
    }

    // ════════════════════════════════════════════════════════════════
    // action drift — counts
    // ════════════════════════════════════════════════════════════════

    @Test
    void actionDrift_countsReflectRepoAndRegistrySizes() {
        ComposioActionConfig a = action("a", true);
        ComposioActionConfig b = action("b", true);
        ComposioActionConfig c = action("c", false);
        when(actionRepo.findAll()).thenReturn(List.of(a, b, c));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of("A", "B", "EXTRA"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals(3, resp.actionDrift().totalInDb());
        assertEquals(2, resp.actionDrift().enabledInDb());
        assertEquals(3, resp.actionDrift().inLiveRegistry());
    }

    // ════════════════════════════════════════════════════════════════
    // registryWasTruncated passthrough
    // ════════════════════════════════════════════════════════════════

    @Test
    void registryWasTruncated_passesThroughFromActionRegistry() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of());
        when(actionRegistry.wasTruncated()).thenReturn(true);

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertTrue(resp.registryWasTruncated(),
                "truncation flag must surface for ops — registry hit the size cap");
    }

    @Test
    void registryWasTruncated_isFalse_whenRegistryNotTruncated() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of());
        when(actionRegistry.wasTruncated()).thenReturn(false);

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertFalse(resp.registryWasTruncated());
    }

    // ════════════════════════════════════════════════════════════════
    // connection coverage gaps — orgsWithoutConnection
    // ════════════════════════════════════════════════════════════════

    @Test
    void orgsWithoutConnection_reportsOrgsWithAgentsButNoComposioConnection() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of());
        // Org-A has a connection; org-B and org-C have agents but no connection.
        when(connectionRepo.findAll()).thenReturn(List.of(connection("org-A", "conn-1", LocalDateTime.now())));
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of("org-A", "org-B", "org-C"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals(List.of("org-B", "org-C"), resp.orgsWithoutConnection(),
                "orgs with agents but no Composio connection must surface — those need onboarding");
    }

    @Test
    void orgsWithoutConnection_isSortedAlphabetically() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of());
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of("org-Z", "org-A", "org-M"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals(List.of("org-A", "org-M", "org-Z"), resp.orgsWithoutConnection());
    }

    @Test
    void orgsWithoutConnection_emptyWhenAllOrgsHaveConnections() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of());
        when(connectionRepo.findAll()).thenReturn(List.of(
                connection("org-A", "conn-A", LocalDateTime.now()),
                connection("org-B", "conn-B", LocalDateTime.now())));
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of("org-A", "org-B"));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertTrue(resp.orgsWithoutConnection().isEmpty(),
                "all-covered case must surface as empty list, not null");
    }

    // ════════════════════════════════════════════════════════════════
    // connection rows shape + ordering
    // ════════════════════════════════════════════════════════════════

    @Test
    void connectionRows_sortedByOrgId() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        LocalDateTime ts = LocalDateTime.of(2026, 5, 24, 10, 0);
        when(connectionRepo.findAll()).thenReturn(List.of(
                connection("org-Z", "conn-Z", ts),
                connection("org-A", "conn-A", ts),
                connection("org-M", "conn-M", ts)));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        assertEquals(3, resp.connections().size());
        assertEquals("org-A", resp.connections().get(0).orgId());
        assertEquals("org-M", resp.connections().get(1).orgId());
        assertEquals("org-Z", resp.connections().get(2).orgId());
    }

    @Test
    void connectionRows_carryOrgIdConnectionIdAndTimestamp() {
        when(actionRepo.findAll()).thenReturn(List.of());
        when(actionRegistry.getEnabledActions()).thenReturn(Set.of());
        when(agentRepo.findDistinctOrgIds()).thenReturn(List.of());
        LocalDateTime ts = LocalDateTime.of(2026, 5, 24, 10, 0);
        when(connectionRepo.findAll()).thenReturn(List.of(connection("org-1", "conn-abc", ts)));

        ConfigDriftResponse resp = service.buildDriftSnapshot();
        ConfigDriftResponse.ConnectionRow row = resp.connections().get(0);
        assertEquals("org-1", row.orgId());
        assertEquals("conn-abc", row.connectionId());
        assertEquals(ts, row.updatedAt());
    }

    // ════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════

    private static ComposioActionConfig action(String name, boolean enabled) {
        ComposioActionConfig a = new ComposioActionConfig();
        a.setActionName(name);
        a.setEnabled(enabled);
        return a;
    }

    private static ComposioConnectionConfig connection(String orgId, String connectionId, LocalDateTime updatedAt) {
        ComposioConnectionConfig c = new ComposioConnectionConfig();
        c.setOrgId(orgId);
        c.setConnectionId(connectionId);
        // updatedAt is set via reflection because there's no setter (likely @LastModifiedDate).
        try {
            java.lang.reflect.Field f = ComposioConnectionConfig.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.set(c, updatedAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test fixture failed to set updatedAt", e);
        }
        return c;
    }
}
