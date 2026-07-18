package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import ai.operativus.agentmanager.core.model.ExtensionRegistrationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit coverage of the write-time SSRF guard on
 * {@link ExtensionController#registerExtension(ExtensionRegistrationDTO)} and
 * {@link ExtensionController#updateExtension(String, ExtensionRegistrationDTO)}.
 *
 * <p>An MCP or WEBHOOK extension URL is operator-supplied and persisted into
 * {@code extension_registration.url}. The persisted URL then drives outbound
 * I/O — MCP via {@link ai.operativus.agentmanager.compute.mcp.McpConnectionPool}
 * at startup reconcile + Redis PubSub re-dispatch, WEBHOOK via the
 * {@code ExtensionHookAdvisor.dispatchHook} path. Without admission-time
 * validation, a tenant admin could persist a URL pointing at loopback /
 * RFC-1918 / 169.254 cloud-metadata; the runtime guards (added in this PR for
 * MCP, already present for WEBHOOK) would catch the connect attempt, but the
 * row would still pollute the registry and the operator would get no immediate
 * feedback. Write-time validation closes that gap.
 *
 * <p>Tests verify the bad URL is REJECTED with 400 AND that the repository's
 * {@code save} is never invoked — the row must not land in the DB even
 * transiently (since other nodes' PubSub listeners would then pick it up).
 */
@ExtendWith(MockitoExtension.class)
class ExtensionControllerSsrfTest {

    @Mock
    private ExtensionRegistrationRepository repository;

    @Mock
    private StringRedisTemplate redisTemplate;

    private ExtensionController controller;

    @BeforeEach
    void setUp() {
        controller = new ExtensionController(repository, redisTemplate);
    }

    @Test
    void registerExtension_RejectsCloudMetadataUrl() {
        ExtensionRegistrationDTO bad = new ExtensionRegistrationDTO(
                "ext-meta", "aws-imds", "MCP",
                "http://169.254.169.254/latest/meta-data/", null, true, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerExtension(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("SSRF"),
                "expected SSRF rejection in reason; got: " + ex.getReason());
        verify(repository, never()).save(any());
    }

    @Test
    void registerExtension_RejectsLoopbackUrl() {
        ExtensionRegistrationDTO bad = new ExtensionRegistrationDTO(
                "ext-local", "self", "MCP",
                "http://127.0.0.1:8080/internal", null, true, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerExtension(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void registerExtension_RejectsDecimalEncodedLoopbackUrl() {
        ExtensionRegistrationDTO bad = new ExtensionRegistrationDTO(
                "ext-decimal", "decimal-bypass", "MCP",
                "http://2130706433/mcp/sse", null, true, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerExtension(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void registerExtension_RejectsRfc1918Url() {
        ExtensionRegistrationDTO bad = new ExtensionRegistrationDTO(
                "ext-int", "internal-mcp", "MCP",
                "http://10.0.0.5:9000/mcp/sse", null, true, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerExtension(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void registerExtension_RejectsNonHttpScheme() {
        ExtensionRegistrationDTO bad = new ExtensionRegistrationDTO(
                "ext-file", "file-leak", "WEBHOOK",
                "file:///etc/passwd", null, true, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerExtension(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void registerExtension_AllowsNullUrl_ForFutureNativeSpiPersistencePath() {
        // NATIVE_SPI rows are merged in at GET-time from ServiceLoader and never persisted
        // via this controller today; defensively pass a null-URL row through to repo to
        // pin the early-return on the SSRF guard (no "URL is required" false rejection).
        ExtensionRegistrationDTO spi = new ExtensionRegistrationDTO(
                "ext-spi", "native-hook", "NATIVE_SPI",
                null, "compiled hook", true, null);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.registerExtension(spi);

        verify(repository).save(any());
    }

    @Test
    void updateExtension_RejectsCloudMetadataUrl() {
        ExtensionRegistrationEntity existing = new ExtensionRegistrationEntity();
        existing.setId("ext-1");
        existing.setUrl("https://legit.example.com/mcp/sse");
        existing.setType("MCP");
        existing.setActive(true);
        existing.setVersion(7L);
        when(repository.findByIdAndOrgId(eq("ext-1"), anyString())).thenReturn(Optional.of(existing));

        // Version 7L matches existing.getVersion() so the optimistic-lock pin passes and
        // execution reaches the SSRF guard. Without the matching version the controller
        // would bail at the version check first and the test would falsely "pass" with
        // a different 400.
        ExtensionRegistrationDTO update = new ExtensionRegistrationDTO(
                "ext-1", "legit", "MCP",
                "http://169.254.169.254/latest/", null, true, 7L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateExtension("ext-1", update));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("SSRF"),
                "expected SSRF rejection in reason; got: " + ex.getReason());
        // Critical: bad URL must never reach saveAndFlush, even with a valid version pin.
        verify(repository, never()).saveAndFlush(any());
    }
}
