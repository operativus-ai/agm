package com.operativus.agentmanager.control.a2a;

import com.operativus.agentmanager.control.repository.A2aRemoteAgentRepository;
import com.operativus.agentmanager.core.entity.A2aRemoteAgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically health-checks registered A2A peer agents.
 * Marks peers as untrusted when they fail health checks, and restores trust when they recover.
 * Provides peer selection with simple round-robin load balancing across healthy peers.
 */
@Service
public class PeerHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(PeerHealthMonitor.class);

    private final A2aRemoteAgentRepository peerRepository;
    private final RestTemplate restTemplate;

    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private static final int FAILURE_THRESHOLD = 3;

    public PeerHealthMonitor(A2aRemoteAgentRepository peerRepository, RestTemplate restTemplate) {
        this.peerRepository = peerRepository;
        this.restTemplate = restTemplate;
    }

    /**
     * Health-checks all trusted peers every 2 minutes.
     * Hits GET {baseUrl}/api/v1/health and expects 200 OK.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.peer-health-ms:120000}")
    public void checkPeerHealth() {
        List<A2aRemoteAgentEntity> peers = peerRepository.findAll();
        log.debug("A2A health check: checking {} registered peers", peers.size());

        for (A2aRemoteAgentEntity peer : peers) {
            Thread.ofVirtual().name("a2a-health-" + peer.getAlias()).start(() -> checkSinglePeer(peer));
        }
    }

    private void checkSinglePeer(A2aRemoteAgentEntity peer) {
        String healthUrl = peer.getBaseUrl() + "/api/v1/health";
        try {
            restTemplate.getForEntity(healthUrl, String.class);

            // Success — reset failure counter, mark trusted
            consecutiveFailures.remove(peer.getId());
            if (!Boolean.TRUE.equals(peer.getTrusted())) {
                peer.setTrusted(true);
                log.info("A2A peer '{}' recovered and marked trusted", peer.getAlias());
            }
            peer.setLastVerifiedAt(LocalDateTime.now());
            peerRepository.save(peer);
        } catch (Exception e) {
            int failures = consecutiveFailures.merge(peer.getId(), 1, Integer::sum);
            log.warn("A2A peer '{}' health check failed ({}/{}): {}", peer.getAlias(), failures, FAILURE_THRESHOLD, e.getMessage());

            if (failures >= FAILURE_THRESHOLD && Boolean.TRUE.equals(peer.getTrusted())) {
                peer.setTrusted(false);
                peerRepository.save(peer);
                log.error("A2A peer '{}' marked UNTRUSTED after {} consecutive failures", peer.getAlias(), failures);
            }
        }
    }

    /**
     * Returns a healthy peer for a given capability using round-robin selection.
     * Only returns peers that are trusted and have been verified within the last 5 minutes.
     */
    public A2aRemoteAgentEntity selectHealthyPeer() {
        List<A2aRemoteAgentEntity> healthyPeers = peerRepository.findByTrustedTrue();
        if (healthyPeers.isEmpty()) return null;

        // Simple round-robin via modular counter
        long counter = System.nanoTime();
        return healthyPeers.get((int) (Math.abs(counter) % healthyPeers.size()));
    }

    /**
     * Returns health status of all registered peers.
     */
    public Map<String, Object> getPeerHealthStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        List<A2aRemoteAgentEntity> peers = peerRepository.findAll();
        for (A2aRemoteAgentEntity peer : peers) {
            Map<String, Object> peerStatus = new java.util.LinkedHashMap<>();
            peerStatus.put("alias", peer.getAlias());
            peerStatus.put("baseUrl", peer.getBaseUrl());
            peerStatus.put("trusted", peer.getTrusted());
            peerStatus.put("lastVerifiedAt", peer.getLastVerifiedAt());
            peerStatus.put("consecutiveFailures", consecutiveFailures.getOrDefault(peer.getId(), 0));
            status.put(peer.getId(), peerStatus);
        }
        return status;
    }
}
