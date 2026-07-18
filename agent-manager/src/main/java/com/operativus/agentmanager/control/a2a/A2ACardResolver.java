package com.operativus.agentmanager.control.a2a;

import com.operativus.agentmanager.control.a2a.model.AgentCard;
import com.operativus.agentmanager.control.a2a.model.RemoteAgentRegistration;
import com.operativus.agentmanager.control.repository.A2aRemoteAgentRepository;
import com.operativus.agentmanager.core.entity.A2aRemoteAgentEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain Responsibility: Resolves {@link AgentCard} records for both local agents published
 * by this AGM instance and remote agents registered as A2A peers.
 *
 * Gap 2.1 Implementation: Implements the missing external discovery mechanism. The
 * {@code PlannerOrchestrator} can call {@link #resolveCard(String, String)} to obtain the
 * capability profile of any known agent (local or remote) without hardcoded assumptions.
 *
 * Remote resolution fetches {@code GET <baseUrl>/api/v1/a2a/cards/<agentId>} using the
 * registered outbound API key, then caches the result in-memory with a configurable TTL.
 *
 * §22.7 cross-org isolation: peer registrations are scoped by {@code orgId}. The in-memory
 * registry is keyed by {@code (orgId, alias)} so two tenants can share an alias without
 * collision; the DB enforces the same with {@code UNIQUE(org_id, alias)} (migration 025).
 * Callers must pass the requesting tenant's orgId — most often sourced from the
 * {@code X-Org-Id} header at the controller layer. A null orgId means "legacy / unscoped"
 * and is preserved for rows registered before the column existed.
 *
 * Architecture:
 * - Constructor injection only.
 * - No ApplicationEventPublisher.
 * - In-memory registration store ({@code LinkedHashMap} wrapped in {@code synchronizedMap}).
 *   Persisted records are loaded at startup and managed via
 *   {@link #registerRemoteAgent(RemoteAgentRegistration, String)}.
 *
 * State: Stateful (in-memory registration cache).
 */
@Service
public class A2ACardResolver {

    private static final Logger log = LoggerFactory.getLogger(A2ACardResolver.class);

    /** Header key sent when calling remote A2A peers. */
    public static final String A2A_API_KEY_HEADER = "X-A2A-Api-Key";

    /** Suffix appended to a remote base URL to fetch a card. */
    private static final String CARDS_PATH = "/api/v1/a2a/cards/";

    /**
     * Composite in-memory registry key — §22.7 scopes peer aliases by tenant.
     * A null orgId represents legacy rows without a tenant stamp.
     */
    private record OrgAlias(String orgId, String alias) {}

    /**
     * Bounded LRU in-process registry: (orgId, alias) → registration.
     * Evicts the eldest entry once the configured max is reached, preventing unbounded growth
     * in long-lived deployments with high peer churn.
     * Wrapped in {@code synchronizedMap} because {@link LinkedHashMap} is not thread-safe.
     */
    private final int maxRegistrySize;
    private final Map<OrgAlias, RemoteAgentRegistration> remoteRegistry;

    private final AgentRegistry agentRegistry;
    private final RestTemplate restTemplate;
    private final A2aRemoteAgentRepository peerRepository;

    public A2ACardResolver(AgentRegistry agentRegistry, RestTemplate restTemplate,
                           A2aRemoteAgentRepository peerRepository,
                           @org.springframework.beans.factory.annotation.Value("${agentmanager.a2a.max-registry-size:500}") int maxRegistrySize) {
        this.agentRegistry = agentRegistry;
        this.restTemplate = restTemplate;
        this.peerRepository = peerRepository;
        this.maxRegistrySize = maxRegistrySize;
        this.remoteRegistry = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<OrgAlias, RemoteAgentRegistration> eldest) {
                boolean evict = size() > maxRegistrySize;
                if (evict) log.warn("A2ACardResolver: remote registry full ({}) — evicting eldest key={}",
                    maxRegistrySize, eldest.getKey());
                return evict;
            }
        });
    }

    /**
     * @summary Primes the in-memory registry from {@code a2a_remote_agents} at startup.
     * @logic Ensures peers persisted by previous runs are immediately discoverable
     *        without re-registration, and that {@link PeerHealthMonitor} (which
     *        also reads the table) shares a single source of truth with this
     *        resolver. Failures are logged and swallowed — startup must not be
     *        blocked by transient DB issues.
     */
    @PostConstruct
    public void primeRegistryFromDatabase() {
        try {
            List<A2aRemoteAgentEntity> persisted = peerRepository.findAll();
            for (A2aRemoteAgentEntity entity : persisted) {
                remoteRegistry.put(new OrgAlias(entity.getOrgId(), entity.getAlias()), toRegistration(entity));
            }
            log.info("A2ACardResolver: primed in-memory registry with {} persisted peers", persisted.size());
        } catch (Exception ex) {
            log.warn("A2ACardResolver: failed to prime registry from a2a_remote_agents — continuing empty: {}",
                ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * @summary Resolves an {@link AgentCard} for the given agent identifier, scoped by org.
     * @logic
     * 1. Attempt to map a local agent from {@code AgentRegistry}.
     * 2. If not found locally, search registered remote peers (within {@code orgId})
     *    by remote agent ID or alias.
     * 3. Fetch the card from the remote's REST endpoint using its outbound API key.
     * 4. Cache the fetched card back into the registration record for subsequent calls.
     *
     * @param agentId Local agent ID, remote agent ID, or registered alias.
     * @param orgId   Tenant scope for remote resolution. Null means legacy/unscoped.
     * @return {@link Optional} containing the resolved card, or empty if not discoverable.
     */
    public Optional<AgentCard> resolveCard(String agentId, String orgId) {
        // Local resolution
        AgentDefinition local = agentRegistry.findById(agentId, orgId);
        if (local != null) {
            return Optional.of(buildLocalCard(local));
        }

        // Remote resolution via registered peers
        RemoteAgentRegistration registration = findRegistrationFor(agentId, orgId);
        if (registration != null) {
            return fetchRemoteCard(registration, orgId);
        }

        log.debug("A2ACardResolver: no local or remote agent found for id={} orgId={}", agentId, orgId);
        return Optional.empty();
    }

    /**
     * @summary Publishes this AGM instance's own card for a given local agent.
     * @logic Builds the {@link AgentCard} from the local {@link AgentRegistry} entry.
     *        Returns empty if the agent is unknown (not published).
     */
    public Optional<AgentCard> publishLocalCard(String agentId) {
        AgentDefinition def = agentRegistry.findById(agentId,
                com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId());
        if (def == null) return Optional.empty();
        return Optional.of(buildLocalCard(def));
    }

    /**
     * @summary Lists all local agents as {@link AgentCard} records.
     * @logic Iterates active agents from the registry and maps each to a card.
     */
    public List<AgentCard> listLocalCards() {
        return agentRegistry.findAll(false,
                com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId()).stream()
            .map(this::buildLocalCard)
            .toList();
    }

    /**
     * @summary Registers a remote A2A peer agent for future resolution and delegation
     *          within the given tenant scope.
     * @logic Persists the registration to {@code a2a_remote_agents} (stamping {@code org_id}
     *        and encrypting the outbound API key via
     *        {@link com.operativus.agentmanager.control.security.OutboundApiKeyConverter})
     *        and updates the in-memory cache keyed by {@code (orgId, alias)}. The persisted
     *        row is what {@link PeerHealthMonitor} reads, so a single registration call is
     *        still the source of truth for both discovery and health-checking.
     *
     * @param registration the immutable peer registration payload.
     * @param orgId        tenant scope — null is accepted for legacy callers but discouraged.
     */
    public void registerRemoteAgent(RemoteAgentRegistration registration, String orgId) {
        A2aRemoteAgentEntity entity = peerRepository.findByAliasAndOrgId(registration.alias(), orgId)
            .orElseGet(A2aRemoteAgentEntity::new);
        entity.setId(entity.getId() != null ? entity.getId() : registration.id());
        entity.setRemoteAgentId(registration.remoteAgentId());
        entity.setBaseUrl(registration.baseUrl());
        entity.setAlias(registration.alias());
        entity.setOrgId(orgId);
        entity.setOutboundApiKey(registration.trustedApiKey());
        entity.setSecurityTier(entity.getSecurityTier() != null ? entity.getSecurityTier() : 1);
        entity.setTrusted(entity.getTrusted() != null ? entity.getTrusted() : Boolean.TRUE);
        peerRepository.save(entity);

        remoteRegistry.put(new OrgAlias(orgId, registration.alias()), registration);
        log.info("A2ACardResolver: registered remote agent alias={} remoteId={} orgId={} baseUrl={}",
            registration.alias(), registration.remoteAgentId(), orgId, registration.baseUrl());
    }

    /**
     * @summary Removes a remote peer registration by alias within the given tenant scope.
     * @logic Deletes the persisted row and the in-memory entry together so
     *        {@link PeerHealthMonitor} stops health-checking a deregistered peer.
     *        Deregistration is scoped — a caller in org A cannot drop a peer registered
     *        under org B.
     */
    public boolean deregisterRemoteAgent(String alias, String orgId) {
        boolean removedFromMemory = remoteRegistry.remove(new OrgAlias(orgId, alias)) != null;
        boolean removedFromDb = peerRepository.findByAliasAndOrgId(alias, orgId)
            .map(entity -> { peerRepository.delete(entity); return true; })
            .orElse(false);
        return removedFromMemory || removedFromDb;
    }

    /**
     * @summary Maps a persisted {@link A2aRemoteAgentEntity} back to the in-memory
     *          {@link RemoteAgentRegistration} record used by the resolver.
     */
    private RemoteAgentRegistration toRegistration(A2aRemoteAgentEntity entity) {
        Instant registeredAt = entity.getCreatedAt() != null
            ? entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()
            : Instant.now();
        Instant lastVerifiedAt = entity.getLastVerifiedAt() != null
            ? entity.getLastVerifiedAt().atZone(ZoneId.systemDefault()).toInstant()
            : null;
        return new RemoteAgentRegistration(
            entity.getId(),
            entity.getRemoteAgentId(),
            entity.getBaseUrl(),
            entity.getAlias(),
            entity.getOutboundApiKey(),
            null,
            registeredAt,
            lastVerifiedAt
        );
    }

    /**
     * @summary Returns all remote peer registrations for the given tenant.
     * @logic §22.7 — callers see only the peers registered under their own orgId.
     *        A null orgId matches legacy rows registered before the column existed.
     */
    public List<RemoteAgentRegistration> listRemoteRegistrations(String orgId) {
        synchronized (remoteRegistry) {
            List<RemoteAgentRegistration> scoped = new ArrayList<>();
            for (Map.Entry<OrgAlias, RemoteAgentRegistration> entry : remoteRegistry.entrySet()) {
                if (Objects.equals(entry.getKey().orgId(), orgId)) {
                    scoped.add(entry.getValue());
                }
            }
            return scoped;
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * @summary Fetches an {@link AgentCard} from a remote peer's REST endpoint.
     * @logic
     * Sends {@code GET <baseUrl>/api/v1/a2a/cards/<remoteAgentId>} with the outbound
     * {@code X-A2A-Api-Key} header. On success, updates the cached card in the registration.
     */
    private Optional<AgentCard> fetchRemoteCard(RemoteAgentRegistration registration, String orgId) {
        String url = registration.baseUrl() + CARDS_PATH + registration.remoteAgentId();
        try {
            HttpHeaders headers = new HttpHeaders();
            if (registration.trustedApiKey() != null) {
                headers.set(A2A_API_KEY_HEADER, registration.trustedApiKey());
            }
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<AgentCard> response = restTemplate.exchange(url, HttpMethod.GET, entity, AgentCard.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                AgentCard fetched = response.getBody();
                RemoteAgentRegistration updated = new RemoteAgentRegistration(
                    registration.id(), registration.remoteAgentId(), registration.baseUrl(),
                    registration.alias(), registration.trustedApiKey(),
                    fetched, registration.registeredAt(), Instant.now()
                );
                remoteRegistry.put(new OrgAlias(orgId, registration.alias()), updated);
                log.info("A2ACardResolver: resolved remote card for alias={} remoteId={} orgId={}",
                    registration.alias(), registration.remoteAgentId(), orgId);
                return Optional.of(fetched);
            }
        } catch (RestClientException e) {
            log.warn("A2ACardResolver: failed to fetch remote card from {} — falling back to cached. Error: {}",
                url, e.getMessage());
            // Fall through to cached card if available
        }

        // Return stale cached card if present
        if (registration.lastResolvedCard() != null) {
            log.debug("A2ACardResolver: serving stale cached card for alias={}", registration.alias());
            return Optional.of(registration.lastResolvedCard());
        }
        return Optional.empty();
    }

    /**
     * @summary Locates a remote registration matching the given agent identifier (alias or
     *          remote agent ID) within the caller's tenant scope.
     */
    private RemoteAgentRegistration findRegistrationFor(String agentId, String orgId) {
        RemoteAgentRegistration byAlias = remoteRegistry.get(new OrgAlias(orgId, agentId));
        if (byAlias != null) return byAlias;

        synchronized (remoteRegistry) {
            return remoteRegistry.entrySet().stream()
                .filter(e -> Objects.equals(e.getKey().orgId(), orgId))
                .map(Map.Entry::getValue)
                .filter(r -> agentId.equals(r.remoteAgentId()))
                .findFirst()
                .orElse(null);
        }
    }

    /**
     * @summary Maps a local {@link AgentDefinition} to an {@link AgentCard}.
     * @logic Extracts tools as capability tokens, reads securityTier and finOpsTokenBudget,
     *        and marks endpointUrl as null (local agents do not require HTTP routing).
     */
    private AgentCard buildLocalCard(AgentDefinition def) {
        List<String> capabilities = new ArrayList<>();
        if (def.tools() != null) capabilities.addAll(def.tools());
        if (Boolean.TRUE.equals(def.isTeam())) capabilities.add("team-orchestration");
        if (Boolean.TRUE.equals(def.requiresPiiRedaction())) capabilities.add("pii-redaction");
        if (Boolean.TRUE.equals(def.memoryEnabled())) capabilities.add("persistent-memory");

        return AgentCard.local(
            def.id(),
            def.name(),
            def.description(),
            capabilities,
            def.securityTier() != null ? def.securityTier() : 1,
            def.modelId(),
            def.finOpsTokenBudget()
        );
    }
}
