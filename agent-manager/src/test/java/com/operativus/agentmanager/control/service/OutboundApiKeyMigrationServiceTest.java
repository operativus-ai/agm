package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.A2aRemoteAgentRepository;
import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import com.operativus.agentmanager.core.entity.A2aRemoteAgentEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins {@link OutboundApiKeyMigrationService} which rotates
 *   encrypted outbound API keys for A2A peer agents under the active encryption key
 *   version. Fires weekly via cron (Sunday 03:00 by default), so bugs here surface
 *   slowly — a silent failure could leave keys stuck at an old version indefinitely,
 *   blocking eventual key retirement.
 *
 *   <p>The class has three behaviorally-distinct paths:
 *   <ol>
 *     <li><b>Gating</b>: {@code scheduledRotation} no-ops when {@code enabled=false}.</li>
 *     <li><b>Passthrough short-circuit</b>: {@code rotateStaleRows} returns 0 without
 *         touching the repo when the converter is in passthrough mode (no encryption
 *         keys configured).</li>
 *     <li><b>Rotation</b>: re-encrypts every row whose {@code keyVersion} differs from
 *         the active version; rows already on the active version are left alone.</li>
 *   </ol>
 *
 *   <p>The contract under test isn't the encryption itself (that's {@code OutboundApiKeyConverter}'s
 *   job, and it runs transparently through the entity getter/setter) — it's the
 *   <em>which rows get touched</em> question. A bug that re-saves ALL rows would mass-rewrite
 *   under the active key (probably fine but wasteful); a bug that re-saves NO stale rows
 *   would silently block rotation forever; a bug that bumps {@code keyVersion} without
 *   touching {@code outboundApiKey} would mismark rows as rotated when they're still
 *   encrypted under the OLD key (the worst case — operator retires the old key thinking
 *   migration is done, and decryption breaks for "rotated" rows).
 *
 * State: Stateless (pure mock-driven; no DB, no Spring context).
 */
public class OutboundApiKeyMigrationServiceTest {

    private final A2aRemoteAgentRepository repository = mock(A2aRemoteAgentRepository.class);
    private final OutboundApiKeyConverter converter = mock(OutboundApiKeyConverter.class);

    // ════════════════════════════════════════════════════════════════
    // scheduledRotation — top-level gate
    // ════════════════════════════════════════════════════════════════

    @Test
    void scheduledRotation_disabled_doesNotTouchConverterOrRepository() {
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, /* enabled= */ false);

        svc.scheduledRotation();

        verifyNoInteractions(repository);
        verifyNoInteractions(converter);
    }

    @Test
    void scheduledRotation_enabled_invokesRotationPath() {
        // When enabled, scheduledRotation must call into the rotation path (which then
        // consults the converter). This pins that the @Scheduled handler isn't
        // accidentally short-circuited or renamed.
        when(converter.isPassthroughMode()).thenReturn(true); // short-circuit at this layer
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, /* enabled= */ true);

        svc.scheduledRotation();

        verify(converter).isPassthroughMode();
    }

    // ════════════════════════════════════════════════════════════════
    // rotateStaleRows — short-circuits
    // ════════════════════════════════════════════════════════════════

    @Test
    void rotateStaleRows_passthroughMode_returnsZeroAndDoesNotQueryRepository() {
        // The converter has no keys configured, so encryption is a no-op. There's nothing
        // to rotate; calling the repo would just generate noise.
        when(converter.isPassthroughMode()).thenReturn(true);
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, true);

        assertEquals(0, svc.rotateStaleRows());
        verifyNoInteractions(repository);
        verify(converter, never()).activeVersion();
    }

    @Test
    void rotateStaleRows_noStaleRows_returnsZeroWithoutSaving() {
        // The most-common steady state: all rows already at active version. The query
        // returns empty, so there's nothing to save. saveAll must NOT be called with
        // an empty list (would generate a useless transaction).
        when(converter.isPassthroughMode()).thenReturn(false);
        when(converter.activeVersion()).thenReturn(2);
        when(repository.findByKeyVersionNot(2)).thenReturn(List.of());
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, true);

        assertEquals(0, svc.rotateStaleRows());
        verify(repository).findByKeyVersionNot(2);
        verify(repository, never()).saveAll(any());
    }

    // ════════════════════════════════════════════════════════════════
    // rotateStaleRows — actual rotation
    // ════════════════════════════════════════════════════════════════

    @Test
    void rotateStaleRows_oneStaleRow_isRotatedAndKeyVersionBumped() {
        when(converter.isPassthroughMode()).thenReturn(false);
        when(converter.activeVersion()).thenReturn(3);
        A2aRemoteAgentEntity stale = peer("peer-1", "secret-v1", 1);
        when(repository.findByKeyVersionNot(3)).thenReturn(List.of(stale));
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, true);

        int rotated = svc.rotateStaleRows();

        assertEquals(1, rotated, "must report 1 row rotated");
        assertEquals(3, stale.getKeyVersion().intValue(),
                "stale row's keyVersion must be bumped to active (3) — without this, "
                        + "the operator can't retire the old key");
    }

    @Test
    void rotateStaleRows_outboundApiKeyIsTouched_soConverterReencryptsOnFlush() {
        // CRITICAL contract: the rotation depends on the setter being CALLED on
        // outboundApiKey, which is what makes Hibernate flag the column dirty so the
        // @Convert listener re-runs with the active key on flush. If the service ever
        // bumped keyVersion WITHOUT touching outboundApiKey, rows would be MISMARKED
        // as rotated while still encrypted under the OLD key — the operator would
        // then retire the old key and decryption breaks.
        when(converter.isPassthroughMode()).thenReturn(false);
        when(converter.activeVersion()).thenReturn(2);
        A2aRemoteAgentEntity stale = spyPeer("peer-1", "secret-v1", 1);
        when(repository.findByKeyVersionNot(2)).thenReturn(List.of(stale));
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, true);

        svc.rotateStaleRows();

        verify(stale, times(1)).getOutboundApiKey();
        verify(stale, times(1)).setOutboundApiKey("secret-v1");
        verify(stale, times(1)).setKeyVersion(2);
    }

    @Test
    void rotateStaleRows_multipleStaleRows_allAreRotated() {
        when(converter.isPassthroughMode()).thenReturn(false);
        when(converter.activeVersion()).thenReturn(2);
        A2aRemoteAgentEntity p1 = peer("peer-1", "key1", 1);
        A2aRemoteAgentEntity p2 = peer("peer-2", "key2", 1);
        A2aRemoteAgentEntity p3 = peer("peer-3", "key3", 1);
        when(repository.findByKeyVersionNot(2)).thenReturn(List.of(p1, p2, p3));
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, true);

        assertEquals(3, svc.rotateStaleRows());
        for (A2aRemoteAgentEntity p : List.of(p1, p2, p3)) {
            assertEquals(2, p.getKeyVersion().intValue(),
                    "every stale row must have keyVersion bumped — peer " + p.getId());
        }
    }

    @Test
    void rotateStaleRows_saveAllReceivesTheStaleList() {
        // Pins that the EXACT list returned by findByKeyVersionNot is what's saved —
        // a bug that constructs a different list or filters mid-loop would silently
        // skip rotations.
        when(converter.isPassthroughMode()).thenReturn(false);
        when(converter.activeVersion()).thenReturn(2);
        A2aRemoteAgentEntity p1 = peer("peer-1", "key1", 1);
        A2aRemoteAgentEntity p2 = peer("peer-2", "key2", 1);
        when(repository.findByKeyVersionNot(2)).thenReturn(List.of(p1, p2));
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, true);

        svc.rotateStaleRows();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<A2aRemoteAgentEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(List.of(p1, p2), captor.getValue(),
                "saveAll must be called with the same list returned by the query");
    }

    @Test
    void rotateStaleRows_queryUsesCurrentActiveVersionFromConverter() {
        // If activeVersion gets cached or stale, the "stale" filter would be wrong.
        // Pin that the query parameter is always read fresh from converter.activeVersion().
        when(converter.isPassthroughMode()).thenReturn(false);
        when(converter.activeVersion()).thenReturn(7);
        when(repository.findByKeyVersionNot(anyInt())).thenReturn(List.of());
        OutboundApiKeyMigrationService svc = new OutboundApiKeyMigrationService(
                repository, converter, true);

        svc.rotateStaleRows();

        verify(repository).findByKeyVersionNot(7);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static A2aRemoteAgentEntity peer(String id, String apiKey, int keyVersion) {
        A2aRemoteAgentEntity e = new A2aRemoteAgentEntity();
        e.setId(id);
        e.setOutboundApiKey(apiKey);
        e.setKeyVersion(keyVersion);
        return e;
    }

    /**
     * Spied peer so the verify(...).setOutboundApiKey + setKeyVersion + getOutboundApiKey
     * call counts pin the rotation contract.
     */
    private static A2aRemoteAgentEntity spyPeer(String id, String apiKey, int keyVersion) {
        return org.mockito.Mockito.spy(peer(id, apiKey, keyVersion));
    }
}
