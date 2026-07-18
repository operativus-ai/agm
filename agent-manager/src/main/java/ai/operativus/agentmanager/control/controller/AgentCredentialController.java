package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.compute.security.AgentIdentityService;
import ai.operativus.agentmanager.control.dto.AgentCredentialRequest;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentCredential;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for managing first-class agent credentials (Agent Identity).
 *
 * <p>Org-ownership guard: every handler calls {@link #requireAgentOwnedByCallerOrg(String)} before
 * touching credentials. This prevents IDOR — a caller in org-A supplying an agentId or
 * credentialId that belongs to org-B. The service layer further scopes credential lookups to the
 * supplied agentId via {@code findByIdAndAgentId}, so both the agent-org boundary and the
 * credential-agent boundary are enforced in depth.</p>
 *
 * <p>Inbound bind uses {@link AgentCredentialRequest} rather than the raw {@link AgentCredential}
 * entity. The DTO closes the mass-assignment vector that previously let a client supply an
 * arbitrary {@code id} — which, under Spring Data JPA's {@code save()} merge semantics,
 * would turn the CREATE into an UPDATE on a different tenant's row, hijacking that
 * credential. See the DTO's javadoc for the full threat model.</p>
 */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/credentials")
public class AgentCredentialController {

    private static final Logger log = LoggerFactory.getLogger(AgentCredentialController.class);
    private final AgentIdentityService agentIdentityService;
    private final AgentRegistry agentRegistry;

    public AgentCredentialController(AgentIdentityService agentIdentityService, AgentRegistry agentRegistry) {
        this.agentIdentityService = agentIdentityService;
        this.agentRegistry = agentRegistry;
    }

    /**
     * @summary Lists all credentials for the given agent.
     */
    @GetMapping
    public ResponseEntity<List<AgentCredential>> getCredentials(@PathVariable String agentId) {
        requireAgentOwnedByCallerOrg(agentId);
        return ResponseEntity.ok(agentIdentityService.getCredentials(agentId));
    }

    /**
     * @summary Gets a single credential by ID.
     */
    @GetMapping("/{credentialId}")
    public ResponseEntity<AgentCredential> getCredential(@PathVariable String agentId,
                                                         @PathVariable String credentialId) {
        requireAgentOwnedByCallerOrg(agentId);
        AgentCredential credential = agentIdentityService.getCredential(credentialId, agentId);
        return ResponseEntity.ok(credential);
    }

    /**
     * @summary Creates a new credential for the agent.
     *
     * <p>The {@code id} is always server-generated and the {@code agentId} is always taken
     * from the path variable — neither is read from the request body, closing the
     * mass-assignment vector that previously let a caller hijack another tenant's
     * credential row by supplying its id.
     */
    @PostMapping
    public ResponseEntity<AgentCredential> createCredential(@PathVariable String agentId,
                                                            @Valid @RequestBody AgentCredentialRequest request) {
        requireAgentOwnedByCallerOrg(agentId);
        log.info("Creating credential for agent '{}', provider '{}'", agentId, request.providerName());

        AgentCredential credential = new AgentCredential();
        credential.setAgentId(agentId);
        applyRequestFields(credential, request);

        AgentCredential created = agentIdentityService.createCredential(credential);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * @summary Updates an existing credential.
     *
     * <p>Identifier-bearing fields ({@code id}, {@code agentId}) are taken from path
     * variables only; the request body cannot mutate them.
     */
    @PutMapping("/{credentialId}")
    public ResponseEntity<AgentCredential> updateCredential(@PathVariable String agentId,
                                                            @PathVariable String credentialId,
                                                            @Valid @RequestBody AgentCredentialRequest request) {
        requireAgentOwnedByCallerOrg(agentId);
        log.info("Updating credential '{}' for agent '{}'", credentialId, agentId);

        AgentCredential update = new AgentCredential();
        update.setAgentId(agentId);
        applyRequestFields(update, request);

        AgentCredential updated = agentIdentityService.updateCredential(credentialId, agentId, update);
        return ResponseEntity.ok(updated);
    }

    /**
     * @summary Deletes a credential.
     */
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<Void> deleteCredential(@PathVariable String agentId,
                                                 @PathVariable String credentialId) {
        requireAgentOwnedByCallerOrg(agentId);
        log.info("Deleting credential '{}' for agent '{}'", credentialId, agentId);
        agentIdentityService.deleteCredential(credentialId, agentId);
        return ResponseEntity.noContent().build();
    }

    // Maps the safe-field subset from the DTO onto the entity. Centralized to keep the
    // create + update bindings in sync — any new field on the DTO needs to be reflected
    // here, and any new field on the entity that should NOT be client-settable simply
    // gets omitted from the DTO.
    private static void applyRequestFields(AgentCredential target, AgentCredentialRequest req) {
        target.setCredentialType(req.credentialType());
        target.setProviderName(req.providerName());
        target.setEncryptedSecret(req.encryptedSecret());
        target.setScopes(req.scopes());
        target.setTokenEndpoint(req.tokenEndpoint());
        target.setClientId(req.clientId());
        target.setExpiresAt(req.expiresAt());
        target.setEnabled(req.enabled());
    }

    private void requireAgentOwnedByCallerOrg(String agentId) {
        String orgId = AgentContextHolder.getOrgId();
        if (agentRegistry.findById(agentId, orgId) == null) {
            throw new ResourceNotFoundException("Agent", agentId);
        }
    }
}
