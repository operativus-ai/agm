package com.operativus.agentmanager.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Domain Responsibility: WireMock-backed replay fixture for the Spring AI HTTP path.
 *   Loads a {@code .cassette.json} file (recorded against a real provider), registers
 *   each stub mapping with an embedded {@link WireMockServer}, and at end-of-test
 *   asserts every stub was hit at least once. This is how {@code ProviderContractTest}
 *   detects upstream contract drift on Spring AI version bumps (decision 4.7).
 * State: Stateful — owns a WireMock server and the set of loaded stub IDs. One
 *   instance per test (call {@link #start()} / {@link #stop()} from
 *   {@code @BeforeEach} / {@code @AfterEach}).
 *
 * Cassette file format (a thin envelope around WireMock's mapping JSON):
 * <pre>{@code
 * {
 *   "springAiVersion": "2.0.0-SNAPSHOT",
 *   "mappings": [ { ...wiremock stub... }, ... ]
 * }
 * }</pre>
 *
 * The {@code springAiVersion} field is metadata the {@code check-cassette-versions.sh}
 * lint job (T055) reads to detect cassettes that are stale relative to the project's
 * declared Spring AI version. {@link CassetteSupport} itself does not enforce
 * staleness — drift is a CI lint, not a test flake.
 *
 * Wiring tests to the fake provider:
 *   - Call {@link #baseUrl()} and use {@code @DynamicPropertySource} to override
 *     {@code spring.ai.openai.base-url} (or the equivalent for the provider under test).
 *   - The Spring AI HTTP client will then route every call to this WireMock server.
 */
public final class CassetteSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WireMockServer server;
    private final List<UUID> loadedStubIds = new ArrayList<>();
    private String springAiVersion;

    public CassetteSupport() {
        this.server = new WireMockServer(options().dynamicPort());
    }

    public CassetteSupport start() {
        server.start();
        return this;
    }

    public void stop() {
        server.stop();
    }

    public int port() {
        return server.port();
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /** Spring AI version metadata read from the cassette, or {@code null} if absent. */
    public String springAiVersion() {
        return springAiVersion;
    }

    /** Loads every stub mapping from the cassette into the running WireMock server. */
    public void load(Path cassettePath) {
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(cassettePath));
            JsonNode versionNode = root.path("springAiVersion");
            this.springAiVersion = versionNode.isMissingNode() || versionNode.isNull()
                    ? null : versionNode.asText();

            JsonNode mappings = root.path("mappings");
            if (!mappings.isArray()) {
                throw new IllegalStateException("Cassette missing 'mappings' array: " + cassettePath);
            }
            for (JsonNode mappingNode : mappings) {
                StubMapping stub = StubMapping.buildFrom(mappingNode.toString());
                server.addStubMapping(stub);
                loadedStubIds.add(stub.getId());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load cassette: " + cassettePath, e);
        }
    }

    /**
     * Asserts every loaded stub was matched at least once and no request hit the server
     * without a matching stub. Run from {@code @AfterEach} (or end-of-test) so a missing
     * stub surfaces as a contract drift signal, not a hidden pass.
     */
    public void verifyAllStubsMatched() {
        List<ServeEvent> events = server.getAllServeEvents();

        List<ServeEvent> unmatched = events.stream()
                .filter(e -> !e.getWasMatched())
                .collect(Collectors.toList());
        if (!unmatched.isEmpty()) {
            String detail = unmatched.stream()
                    .map(e -> e.getRequest().getMethod() + " " + e.getRequest().getUrl())
                    .collect(Collectors.joining(", "));
            throw new AssertionError("Unmatched requests hit cassette: " + detail);
        }

        Set<UUID> hit = events.stream()
                .map(e -> e.getStubMapping() == null ? null : e.getStubMapping().getId())
                .filter(id -> id != null)
                .collect(Collectors.toCollection(HashSet::new));
        List<UUID> unused = loadedStubIds.stream()
                .filter(id -> !hit.contains(id))
                .collect(Collectors.toList());
        if (!unused.isEmpty()) {
            throw new AssertionError("Cassette stubs never matched: " + unused);
        }
    }
}
