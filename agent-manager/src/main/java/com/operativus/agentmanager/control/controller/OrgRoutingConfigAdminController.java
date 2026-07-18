package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.OrgRoutingConfigRequest;
import com.operativus.agentmanager.control.dto.OrgRoutingConfigResponse;
import com.operativus.agentmanager.control.service.RoutingResolverService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: REST surface for the per-org universal-dispatch routing config
 *     (one row per org, drives {@code POST /api/runs}). Gated by
 *     {@code agm.universal-dispatch.enabled=true} — when disabled the bean is not
 *     registered and all paths return 404. Admin role required for every endpoint.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/v1/routing-config")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "agm.universal-dispatch.enabled", havingValue = "true")
public class OrgRoutingConfigAdminController {

    private final RoutingResolverService service;

    public OrgRoutingConfigAdminController(RoutingResolverService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<OrgRoutingConfigResponse> getConfig() {
        return ResponseEntity.ok(service.getConfig());
    }

    @PutMapping
    public ResponseEntity<OrgRoutingConfigResponse> upsertConfig(
            @Valid @RequestBody OrgRoutingConfigRequest request) {
        return ResponseEntity.ok(service.upsertConfig(request));
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteConfig() {
        service.deleteConfig();
        return ResponseEntity.noContent().build();
    }
}
