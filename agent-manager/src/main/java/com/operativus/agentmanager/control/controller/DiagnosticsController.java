package com.operativus.agentmanager.control.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Domain Responsibility: Reports runtime introspection facts about the JVM thread serving
 *   the HTTP request. Purpose-built for ops probes and black-box tests that need to verify
 *   production deployment is actually using virtual threads (rather than silently falling
 *   back to platform threads after a mis-configured profile).
 * State: Stateless
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    @GetMapping("/thread")
    public ResponseEntity<Map<String, Object>> currentThread() {
        Thread t = Thread.currentThread();
        return ResponseEntity.ok(Map.of(
                "virtual", t.isVirtual(),
                "name", t.getName(),
                "daemon", t.isDaemon()));
    }
}
