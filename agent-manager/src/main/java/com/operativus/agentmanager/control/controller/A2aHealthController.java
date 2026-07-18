package com.operativus.agentmanager.control.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Domain Responsibility: A2A peer health probe surface. {@code PeerHealthMonitor.checkSinglePeer}
 *     hits {@code GET {peer.baseUrl}/api/v1/health} every 2 minutes (default) and expects 200 OK
 *     to mark the peer trusted. AGM must serve this contract for itself so it can play as a
 *     peer in an A2A mesh. The endpoint is public (whitelisted in {@code app.security.public-paths})
 *     so cross-org peers can probe without bearer auth.
 *
 *     <p>This is distinct from Spring Boot's {@code /actuator/health} which is the internal
 *     ops health surface. The two paths must NOT be conflated — actuator carries detailed DB /
 *     disk / Liquibase status that should not be exposed to external peers.
 *
 * State: Stateless.
 */
@RestController
@RequestMapping("/api/v1/health")
public class A2aHealthController {

    /**
     * @summary Returns {@code 200 OK} with a minimal status payload to satisfy the A2A peer
     *     health-check contract documented in {@code PeerHealthMonitor.checkSinglePeer}.
     * @logic Intentionally trivial — no DB lookup, no downstream probe. If the JVM is running
     *     and Spring routed the request here, the peer is "up" from A2A's perspective. Detailed
     *     liveness/readiness checks live on {@code /actuator/health}.
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
