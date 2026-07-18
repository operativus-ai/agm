package ai.operativus.agentmanager.compute.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Focused unit coverage of {@link McpConnectionPool#connect(String)} — the connect-by-id
 * seam loads the extension row (url / transport / auth) from the repository, so these tests
 * stub {@code findById} and assert on the pool's logging.
 *
 * <p>Two concerns are covered:
 * <ul>
 *   <li><b>SSRF backstop</b> — write-time validation in {@code ExtensionController} blocks new
 *       SSRF URLs at admission, but startup reconcile + Redis PubSub re-dispatch reach this
 *       method directly for any pre-existing row. The guard ensures no transport is opened
 *       against loopback / RFC-1918 / 169.254 cloud-metadata / non-http(s) targets.</li>
 *   <li><b>Transport + auth selection</b> — the streamable-HTTP branch and the bearer-auth
 *       customizer are constructed without error (the subsequent network failure to a
 *       non-existent server is a normal connect failure, not an SSRF rejection).</li>
 * </ul>
 *
 * <p>Strategy: attach a Logback {@link ListAppender} to the pool's logger so the test can
 * assert on the {@code "SSRF guard:"} rejection and the {@code "Connecting to MCP server"}
 * line. Asserting on {@code activeConnections} state alone is insufficient — a vanilla
 * connection failure ALSO leaves an empty list there.
 */
@ExtendWith(MockitoExtension.class)
class McpConnectionPoolTest {

    @Mock
    private ExtensionRegistrationRepository repository;

    private McpConnectionPool pool;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        pool = new McpConnectionPool(repository);
        Logger logger = (Logger) LoggerFactory.getLogger(McpConnectionPool.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(McpConnectionPool.class);
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    /** Stubs the repository to return an MCP extension row for the given id. */
    private void stubExtension(String id, String url, String transport, String auth) {
        ExtensionRegistrationEntity ext = new ExtensionRegistrationEntity();
        ext.setId(id);
        ext.setType("MCP");
        ext.setUrl(url);
        ext.setActive(true);
        ext.setTransport(transport);
        ext.setAuthSecret(auth);
        lenient().when(repository.findById(id)).thenReturn(Optional.of(ext));
    }

    @Test
    void connect_RejectsCloudMetadataUrl() {
        stubExtension("ext-aws", "http://169.254.169.254/latest/meta-data/", "SSE", null);
        pool.connect("ext-aws");

        assertTrue(pool.getToolCallbacks("ext-aws").isEmpty());
        assertSsrfRejectionLogged("ext-aws");
    }

    @Test
    void connect_RejectsLoopbackUrl() {
        stubExtension("ext-local", "http://127.0.0.1:8080/mcp/sse", "SSE", null);
        pool.connect("ext-local");

        assertTrue(pool.getToolCallbacks("ext-local").isEmpty());
        assertSsrfRejectionLogged("ext-local");
    }

    @Test
    void connect_RejectsDecimalEncodedLoopback() {
        // 2130706433 == 127.0.0.1. JDK URI.getHost returns the literal "2130706433";
        // a substring-equals "127.0.0.1" check would miss it.
        stubExtension("ext-decimal", "http://2130706433/mcp/sse", "SSE", null);
        pool.connect("ext-decimal");

        assertTrue(pool.getToolCallbacks("ext-decimal").isEmpty());
        assertSsrfRejectionLogged("ext-decimal");
    }

    @Test
    void connect_RejectsRfc1918Url() {
        stubExtension("ext-internal", "http://10.0.0.5:9000/mcp/sse", "SSE", null);
        pool.connect("ext-internal");

        assertTrue(pool.getToolCallbacks("ext-internal").isEmpty());
        assertSsrfRejectionLogged("ext-internal");
    }

    @Test
    void connect_RejectsNonHttpScheme() {
        stubExtension("ext-file", "file:///etc/passwd", "SSE", null);
        pool.connect("ext-file");

        assertTrue(pool.getToolCallbacks("ext-file").isEmpty());
        assertSsrfRejectionLogged("ext-file");
    }

    @Test
    void connect_RejectsBlankUrl() {
        stubExtension("ext-blank", "", "SSE", null);
        pool.connect("ext-blank");

        assertTrue(pool.getToolCallbacks("ext-blank").isEmpty());
        assertSsrfRejectionLogged("ext-blank");
    }

    @Test
    void connect_UnknownExtensionId_NoConnectAttempt() {
        pool.connect("ext-missing");

        assertTrue(pool.getToolCallbacks("ext-missing").isEmpty());
        assertTrue(logAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("unknown extension 'ext-missing'")));
    }

    @Test
    void connect_StreamableHttpWithAuth_BuildsTransportAndAttemptsConnect() {
        // Public URL passes the SSRF guard. No server is listening, so initialize() fails and
        // an empty tool list is registered — but the streamable-HTTP branch and the bearer-auth
        // customizer must construct WITHOUT throwing, and the connect must not be SSRF-rejected.
        stubExtension("ext-remote", "https://mcp.example.invalid/mcp/", "STREAMABLE_HTTP", "secret-token-1234");
        pool.connect("ext-remote");

        assertTrue(pool.getToolCallbacks("ext-remote").isEmpty());
        // No SSRF rejection — the failure path was a normal connect failure.
        assertEquals(0, logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("SSRF guard:"))
                .count());
        // The connect log records the chosen transport and auth presence (never the secret).
        assertTrue(logAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("transport=STREAMABLE_HTTP")
                        && e.getFormattedMessage().contains("auth=yes")));
        assertTrue(logAppender.list.stream()
                .noneMatch(e -> e.getFormattedMessage().contains("secret-token-1234")),
                "auth secret must never appear in logs");
    }

    @Test
    void connect_UnknownTransport_RefusesConnection() {
        stubExtension("ext-bad-transport", "https://mcp.example.invalid/mcp/", "GRPC", null);
        pool.connect("ext-bad-transport");

        assertTrue(pool.getToolCallbacks("ext-bad-transport").isEmpty());
        assertTrue(logAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("unknown transport 'GRPC'")));
    }

    // ---- Tenant scoping (#1132) ----------------------------------------------------------

    /**
     * The security crux: an agent must only see MCP tools from extensions in its own org.
     * Connections can't be opened against real MCP servers in a unit test, so the pool's two
     * internal maps are seeded directly to exercise the {@code orgId} filter in isolation.
     */
    @Test
    void getToolCallbacksForOrg_returnsOnlyCallersOrgTools() throws Exception {
        ToolCallback toolA = mock(ToolCallback.class);
        ToolCallback toolB = mock(ToolCallback.class);
        seedConnection("ext-a", "org-a", List.of(toolA));
        seedConnection("ext-b", "org-b", List.of(toolB));

        assertEquals(List.of(toolA), pool.getToolCallbacksForOrg("org-a"),
                "org-a must see only its own extension's tools");
        assertEquals(List.of(toolB), pool.getToolCallbacksForOrg("org-b"),
                "org-b must see only its own extension's tools");
        assertTrue(pool.getToolCallbacksForOrg("org-c").isEmpty(),
                "an org with no extensions sees no MCP tools");
        assertEquals(2, pool.getAllToolCallbacks().size(),
                "getAllToolCallbacks remains the cross-org admin/global view");
    }

    @Test
    void getToolCallbacksForOrg_nullOrBlankOrg_failsClosed() {
        assertTrue(pool.getToolCallbacksForOrg(null).isEmpty(),
                "null org must fail closed (no tool leakage), not return everything");
        assertTrue(pool.getToolCallbacksForOrg("   ").isEmpty(),
                "blank org must fail closed");
    }

    @SuppressWarnings("unchecked")
    private void seedConnection(String extensionId, String orgId, List<ToolCallback> tools) throws Exception {
        var active = McpConnectionPool.class.getDeclaredField("activeConnections");
        active.setAccessible(true);
        ((Map<String, List<ToolCallback>>) active.get(pool)).put(extensionId, tools);
        var orgMap = McpConnectionPool.class.getDeclaredField("extensionOrgId");
        orgMap.setAccessible(true);
        ((Map<String, String>) orgMap.get(pool)).put(extensionId, orgId);
    }

    private void assertSsrfRejectionLogged(String extensionId) {
        long matches = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("SSRF guard:"))
                .filter(e -> e.getFormattedMessage().contains(extensionId))
                .count();
        assertEquals(1, matches,
                "expected exactly one 'SSRF guard:' rejection log for extension '" + extensionId
                        + "'; actual log messages were:\n  - "
                        + logAppender.list.stream()
                                .map(ILoggingEvent::getFormattedMessage)
                                .reduce((a, b) -> a + "\n  - " + b)
                                .orElse("(none)"));
    }
}
