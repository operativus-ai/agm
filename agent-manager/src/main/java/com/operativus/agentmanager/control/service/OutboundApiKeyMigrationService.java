package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.A2aRemoteAgentRepository;
import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import com.operativus.agentmanager.core.entity.A2aRemoteAgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Domain Responsibility: §22.6 scheduled outbound API key rotation for A2A peer
 * credentials stored in {@code a2a_remote_agents.outbound_api_key}. Finds rows whose
 * {@code key_version} does not match the converter's active version and re-encrypts
 * them under the active key.
 *
 * How rotation works:
 *  1. Operator adds a new version to {@code agm.security.outbound-key-encryption.keys}
 *     while keeping the old version(s) so existing rows remain decryptable.
 *  2. Operator flips {@code agm.security.outbound-key-encryption.active-version} to
 *     the new version and restarts the app.
 *  3. On the next scheduled fire, this service finds rows still at the old version,
 *     reads each one (the converter transparently decrypts with the row's original
 *     version), re-saves it (the converter encrypts with the active version), and
 *     updates {@code key_version} to the active version.
 *  4. Once all rows report the active version, the operator can retire the old
 *     key from the keys map.
 *
 * Gating:
 *  - Disabled unless {@code agm.security.outbound-key-rotation.enabled=true}.
 *  - No-op when the converter is in passthrough mode (no keys configured).
 *  - No-op when all rows are already at the active version.
 *
 * State: Stateless (scheduled task).
 */
@Component
public class OutboundApiKeyMigrationService {

    private static final Logger log = LoggerFactory.getLogger(OutboundApiKeyMigrationService.class);

    private final A2aRemoteAgentRepository repository;
    private final OutboundApiKeyConverter  converter;
    private final boolean                  enabled;

    public OutboundApiKeyMigrationService(
            A2aRemoteAgentRepository repository,
            OutboundApiKeyConverter converter,
            @Value("${agm.security.outbound-key-rotation.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.converter  = converter;
        this.enabled    = enabled;
    }

    /**
     * @summary Scheduled entry point — re-encrypts stale rows under the active key.
     * @logic Default schedule: weekly at Sunday 03:00. Override via
     *        {@code agm.security.outbound-key-rotation.cron}.
     */
    @Scheduled(cron = "${agm.security.outbound-key-rotation.cron:0 0 3 * * SUN}")
    public void scheduledRotation() {
        if (!enabled) return;
        int rotated = rotateStaleRows();
        if (rotated > 0) {
            log.info("OutboundApiKeyMigrationService: rotated {} peer row(s) to key version {}.",
                     rotated, converter.activeVersion());
        }
    }

    /**
     * @summary Re-encrypts every peer row whose {@code key_version} differs from the
     *          converter's active version and returns the count rotated.
     * @logic Public entry for tests and operator-triggered manual rotation. Reading
     *        each row decrypts with its stored version; saving re-encrypts under the
     *        active version; the {@code key_version} column is updated in the same
     *        transaction.
     */
    @Transactional
    public int rotateStaleRows() {
        if (converter.isPassthroughMode()) return 0;

        int active = converter.activeVersion();
        List<A2aRemoteAgentEntity> stale = repository.findByKeyVersionNot(active);
        if (stale.isEmpty()) return 0;

        for (A2aRemoteAgentEntity peer : stale) {
            // Touch outboundApiKey through setter so Hibernate flags the column dirty
            // and the converter re-runs on flush. Reading the getter already decrypted
            // using the peer's original key version.
            String plaintext = peer.getOutboundApiKey();
            peer.setOutboundApiKey(plaintext);
            peer.setKeyVersion(active);
        }
        repository.saveAll(stale);
        return stale.size();
    }
}
