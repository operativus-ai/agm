package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.ProviderCredentialRequest;
import com.operativus.agentmanager.control.dto.ProviderCredentialResponse;
import com.operativus.agentmanager.control.dto.ProviderCredentialTestRequest;
import com.operativus.agentmanager.control.dto.ProviderCredentialTestResponse;
import com.operativus.agentmanager.control.security.CallerContext;
import com.operativus.agentmanager.control.service.ProviderCredentialService;
import com.operativus.agentmanager.control.service.ProviderCredentialTestService;
import com.operativus.agentmanager.core.entity.ProviderCredential;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Domain Responsibility: Admin REST surface for per-(org, provider) LLM API key CRUD.
 *     Caller's org is always derived from {@link CallerContext}; never from the body
 *     or path so that admins cannot impersonate another tenant's credential store.
 *     {@code apiKey} is never returned in plaintext — see {@link ProviderCredentialResponse}.
 * State: Stateless.
 */
@RestController
@RequestMapping("/api/v1/provider-credentials")
@PreAuthorize("hasRole('ADMIN')")
public class ProviderCredentialAdminController {

    private final ProviderCredentialService service;
    private final ProviderCredentialTestService testService;

    public ProviderCredentialAdminController(ProviderCredentialService service,
                                             ProviderCredentialTestService testService) {
        this.service = service;
        this.testService = testService;
    }

    @GetMapping
    public ResponseEntity<List<ProviderCredentialResponse>> list() {
        String orgId = Objects.requireNonNull(CallerContext.resolveCallerOrgId(), "caller orgId required");
        List<ProviderCredentialResponse> body = service.listForOrg(orgId).stream()
                .map(ProviderCredentialResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProviderCredentialResponse> get(@PathVariable String id) {
        String orgId = Objects.requireNonNull(CallerContext.resolveCallerOrgId(), "caller orgId required");
        return service.findById(id)
                .filter(row -> orgId.equals(row.getOrgId()))
                .map(ProviderCredentialResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ProviderCredentialResponse> upsert(@Valid @RequestBody ProviderCredentialRequest request) {
        String orgId = Objects.requireNonNull(CallerContext.resolveCallerOrgId(), "caller orgId required");
        ProviderCredential saved = service.upsert(orgId, request.provider(), request.apiKey(), request.label());
        return ResponseEntity.status(201).body(ProviderCredentialResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProviderCredentialResponse> update(@PathVariable String id,
                                                              @Valid @RequestBody ProviderCredentialRequest request) {
        String orgId = Objects.requireNonNull(CallerContext.resolveCallerOrgId(), "caller orgId required");
        ProviderCredential existing = service.findById(id)
                .filter(row -> orgId.equals(row.getOrgId()))
                .orElseThrow(() -> new IllegalArgumentException("ProviderCredential not found: " + id));
        if (!existing.getProvider().equals(request.provider())) {
            throw new IllegalArgumentException("Cannot change provider on an existing credential; delete and recreate instead");
        }
        // Blank apiKey => keep the stored key (server never returns it, so editing the label
        // alone must not force a re-type). A non-blank key rotates it.
        ProviderCredential saved = service.update(orgId, request.provider(), request.apiKey(), request.label());
        return ResponseEntity.ok(ProviderCredentialResponse.from(saved));
    }

    /**
     * @summary Live "test connection" probe for a provider credential against a specific model.
     * @logic Resolves the key to test (body {@code apiKey} if present, else the stored
     *     {@code (org, provider)} key), fires one minimal completion, and always responds
     *     200 OK — pass/fail is encoded in the body's {@code success} + {@code message},
     *     matching the {@code POST /api/models/{id}/test} contract so the UI renders a
     *     diagnostic line rather than parsing an HTTP error.
     */
    @PostMapping("/test")
    public ResponseEntity<ProviderCredentialTestResponse> test(@Valid @RequestBody ProviderCredentialTestRequest request) {
        String orgId = Objects.requireNonNull(CallerContext.resolveCallerOrgId(), "caller orgId required");
        return ResponseEntity.ok(testService.test(orgId, request.provider(), request.apiKey(), request.model()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        String orgId = Objects.requireNonNull(CallerContext.resolveCallerOrgId(), "caller orgId required");
        service.findById(id)
                .filter(row -> orgId.equals(row.getOrgId()))
                .ifPresent(row -> service.delete(row.getId()));
        return ResponseEntity.noContent().build();
    }
}
