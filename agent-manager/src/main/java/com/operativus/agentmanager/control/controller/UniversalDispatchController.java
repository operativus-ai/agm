package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.DispatchRunRequest;
import com.operativus.agentmanager.control.security.MediaUrlFetcher;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.registry.RoutingResolver;
import com.operativus.agentmanager.core.security.SsrfGuard;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Universal-dispatch entry point ({@code POST /api/runs}). Accepts
 *     a run request with no {@code agentId} in the URL, resolves the target agent via
 *     {@link RoutingResolver} (default-router team → LLM classifier → semantic/substring
 *     classifier → fallback), then delegates to {@link AgentOperations#run} (sync) or
 *     {@link AgentOperations#stream} (SSE) with the resolved id. Gated by
 *     {@code agm.universal-dispatch.enabled=true} — when off the bean is not registered
 *     and the route returns 404 naturally.
 *
 *     <p>DR-FR-5 adds the streaming variant ({@code POST /api/runs/stream}) and accepts
 *     a {@code media} array on both endpoints (passed through to the resolved agent's
 *     runtime). Resolution happens BEFORE the SSE channel opens — a 404 on resolution
 *     surfaces as a JSON error response, not a half-opened event stream.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/runs")
@ConditionalOnProperty(name = "agm.universal-dispatch.enabled", havingValue = "true")
public class UniversalDispatchController {

    private static final Logger log = LoggerFactory.getLogger(UniversalDispatchController.class);

    private final RoutingResolver routingResolver;
    private final AgentOperations agentOperations;
    private final MediaUrlFetcher mediaUrlFetcher;

    public UniversalDispatchController(RoutingResolver routingResolver,
                                       AgentOperations agentOperations,
                                       MediaUrlFetcher mediaUrlFetcher) {
        this.routingResolver = routingResolver;
        this.agentOperations = agentOperations;
        this.mediaUrlFetcher = mediaUrlFetcher;
    }

    @PostMapping
    public ResponseEntity<RunResponse> dispatch(@Valid @RequestBody DispatchRunRequest request) {
        ResolvedDispatch rd = resolveOrThrow(request);
        log.info("Universal dispatch: org={}, resolved agentId={}, session={}",
                rd.orgId, rd.agentId, rd.sessionId);
        RunResponse response = agentOperations.run(
                rd.agentId, request.message(), rd.media, rd.sessionId, rd.userId, rd.orgId,
                rd.followups, request.options());
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentStreamEvent> stream(@Valid @RequestBody DispatchRunRequest request) {
        ResolvedDispatch rd = resolveOrThrow(request);
        log.info("Universal dispatch (stream): org={}, resolved agentId={}, session={}",
                rd.orgId, rd.agentId, rd.sessionId);
        return agentOperations.stream(
                rd.agentId, request.message(), rd.media, rd.sessionId, rd.userId, rd.orgId,
                rd.followups, request.options());
    }

    private ResolvedDispatch resolveOrThrow(DispatchRunRequest request) {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new BusinessValidationException(
                    "Missing org context — POST /api/runs requires an authenticated principal");
        }
        if (request.message() == null || request.message().isBlank()) {
            throw new BusinessValidationException("Missing required field: message");
        }
        String userId = resolveCallerUserId();
        String resolvedAgentId = routingResolver.resolveAgentId(orgId, userId, request.message());
        if (resolvedAgentId == null) {
            throw new ResourceNotFoundException("agent for dispatch",
                    "no default_router, classifier match, or fallback for org " + orgId);
        }
        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        boolean followups = request.generateFollowups() != null && request.generateFollowups();
        return new ResolvedDispatch(orgId, userId, resolvedAgentId, sessionId, followups,
                mapMedia(request.media(), mediaUrlFetcher));
    }

    private static String resolveCallerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getId() != null ? ud.getId().toString() : ud.getUsername();
        }
        return null;
    }

    // Package-private (not private) so MediaMappingSsrfTest can invoke directly without
    // the heavy controller-injection setup. Pure mapping function — fetcher is the only
    // dependency and is threaded through as a parameter so tests can pass null when the
    // assertion is for the reject-before-fetch branch (SSRF guard, base64-inline).
    static List<Media> mapMedia(List<DispatchRunRequest.MediaInput> inputs,
                                MediaUrlFetcher fetcher) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        return inputs.stream()
                .map(in -> {
                    try {
                        MimeType mimeType = MimeTypeUtils.parseMimeType(in.type());
                        if (in.data() != null && in.data().startsWith("http")) {
                            // SSRF guard (RFC-1918 / loopback / 169.254 / 0.0.0.0) — must pass
                            // before any network I/O. Without this check, an attacker could
                            // supply http://169.254.169.254/... (cloud metadata) or any
                            // private-network URL reachable from the AGM host; the response
                            // bytes would land in the LLM prompt and likely echo back in the
                            // model's output. The prefix check above only blocks file: schemes.
                            String ssrfError = SsrfGuard.validate(in.data(), false);
                            if (ssrfError != null) {
                                throw new BusinessValidationException(
                                        "Media URL rejected by SSRF guard: " + ssrfError);
                            }
                            // DoS guard — MediaUrlFetcher enforces connect + read timeouts and a
                            // payload-size cap. Returning ByteArrayResource (not UrlResource)
                            // means Spring AI's media pipeline never sees a URL and so cannot
                            // run its own unbounded eager-fetch path on it.
                            byte[] bytes = fetcher.fetch(in.data());
                            return new Media(mimeType, new ByteArrayResource(bytes));
                        }
                        byte[] bytes = Base64.getDecoder().decode(in.data());
                        return new Media(mimeType, new ByteArrayResource(bytes));
                    } catch (BusinessValidationException e) {
                        throw e;
                    } catch (MediaUrlFetcher.MediaFetchException e) {
                        throw new BusinessValidationException(
                                "Media URL fetch refused: " + e.getMessage());
                    } catch (Exception e) {
                        throw new BusinessValidationException(
                                "Invalid media input on universal-dispatch request: " + e.getMessage());
                    }
                })
                .collect(Collectors.toList());
    }

    private record ResolvedDispatch(String orgId, String userId, String agentId, String sessionId,
                                     boolean followups, List<Media> media) {}
}
