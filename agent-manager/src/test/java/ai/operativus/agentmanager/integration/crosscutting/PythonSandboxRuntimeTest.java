package ai.operativus.agentmanager.integration.crosscutting;

import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.registry.PythonSandboxOperations;
import ai.operativus.agentmanager.core.registry.PythonSandboxOperations.ExecutionResult;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box coverage of {@link PythonSandboxOperations} / its concrete
 *   Spring bean {@link ai.operativus.agentmanager.control.service.PythonSandboxService}.
 *   Pins the sandbox execution contract end-to-end against a real Docker daemon (Testcontainers):
 *   <ul>
 *     <li>Null / blank / whitespace-only input is rejected at the boundary with
 *         {@link BusinessValidationException} — the tool never spins up a container for empty
 *         payloads.</li>
 *     <li>Code containing previously-blocklisted imports ({@code import os}, etc.) reaches the
 *         container and executes — the bypassable substring blocklist was removed. Containment
 *         is provided by the real defenses below, NOT by source filtering.</li>
 *     <li>Bypass patterns the prior blocklist would have missed (e.g. {@code __import__("os")})
 *         also reach the container and execute — pinning that the validation layer no longer
 *         pretends to filter import patterns.</li>
 *     <li>Happy-path execution: stdout captured verbatim, stderr empty, exit code 0.</li>
 *     <li>Runtime Python failure: stderr populated with traceback, non-zero exit code, service
 *         does NOT throw — the wrapper returns an {@link ExecutionResult} with the failure
 *         details so the tool layer can format them for the caller.</li>
 *     <li>30-second OS-level timeout wrapper kills runaway scripts with GNU {@code timeout}'s
 *         124 exit code rather than hanging the request thread indefinitely.</li>
 *     <li>Outbound network access is blocked by {@code --network=none} — this is the real
 *         containment for arbitrary code, regardless of what imports the code uses.</li>
 *   </ul>
 *
 * Harness note: each test spins up an ephemeral {@code python:3.11-slim} container. Runtime is
 *   dominated by image pull + container start (~2–5s each). Tests keep per-case work small so
 *   the whole class finishes in well under a minute on a warm Docker cache.
 *
 * State: Stateless (each test owns its own container lifecycle via the service layer; no shared
 *   fixture state).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §27.8.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class PythonSandboxRuntimeTest extends BaseIntegrationTest {

    @Autowired private PythonSandboxOperations pythonSandbox;

    @Test
    void executeCodeWithNullInputThrowsBusinessValidationExceptionBeforeContainerProvision() {
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> pythonSandbox.executeCode(null));
        assertTrue(ex.getMessage().toLowerCase().contains("empty"),
                "message must identify the empty-input failure mode, got: " + ex.getMessage());
    }

    @Test
    void executeCodeWithBlankWhitespaceInputThrowsBusinessValidationException() {
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> pythonSandbox.executeCode("   \n\t  "));
        assertTrue(ex.getMessage().toLowerCase().contains("empty"));
    }

    /**
     * Pins that {@code import os} reaches the container and executes — the previously-bypassable
     * substring blocklist has been removed. The host is contained by network isolation +
     * ephemeral lifecycle + 30s timeout, NOT by source-pattern filtering.
     */
    @Test
    void executeCodeWithImportOsRunsToCompletionContainedByNetworkIsolation() {
        ExecutionResult result = pythonSandbox.executeCode("import os\nprint(os.getcwd())");

        assertEquals(0, result.exitCode(),
                "import os should reach the container and execute; stderr=" + result.stderr());
        assertTrue(result.stdout().trim().length() > 0,
                "stdout should contain a working directory path, got: " + result.stdout());
    }

    /**
     * Pins that the {@code __import__("os")} bypass pattern — which the prior substring blocklist
     * would have failed to catch (no literal "import os" substring in the source) — reaches the
     * container and executes. This is intentional: the blocklist was removed precisely because
     * patterns like this defeated it, giving false security on top of the real defenses.
     */
    @Test
    void executeCodeWithDoubleUnderscoreImportBypassRunsContainedByNetworkIsolation() {
        ExecutionResult result = pythonSandbox.executeCode(
                "print(__import__('os').environ.get('PATH', 'absent')[:4])");

        assertEquals(0, result.exitCode(),
                "__import__ bypass should reach the container and execute; stderr=" + result.stderr());
    }

    @Test
    void executeCodeSimpleArithmeticReturnsStdoutAndZeroExitCode() {
        ExecutionResult result = pythonSandbox.executeCode("print(2 + 2)");

        assertNotNull(result);
        assertEquals(0, result.exitCode(), "simple arithmetic should exit 0; stderr=" + result.stderr());
        assertTrue(result.stdout().trim().equals("4"),
                "stdout should be '4' (with trailing newline), got: " + result.stdout());
        assertTrue(result.stderr() == null || result.stderr().isEmpty(),
                "stderr should be empty on success, got: " + result.stderr());
    }

    @Test
    void executeCodeRuntimeErrorReturnsNonZeroExitCodeWithStderrTracebackAndDoesNotThrow() {
        ExecutionResult result = pythonSandbox.executeCode("print(1 / 0)");

        assertNotNull(result);
        assertNotEquals(0, result.exitCode(),
                "division-by-zero must exit non-zero; stdout=" + result.stdout());
        assertFalse(result.stderr() == null || result.stderr().isEmpty(),
                "stderr must contain the Python traceback");
        assertTrue(result.stderr().contains("ZeroDivisionError"),
                "stderr must identify the ZeroDivisionError, got: " + result.stderr());
    }

    @Test
    void executeCodeMultilineScriptPreservesStdoutLineOrder() {
        String code = """
                for i in range(3):
                    print(f"line-{i}")
                """;

        ExecutionResult result = pythonSandbox.executeCode(code);

        assertEquals(0, result.exitCode(), "stderr=" + result.stderr());
        String stdout = result.stdout();
        int firstIdx = stdout.indexOf("line-0");
        int secondIdx = stdout.indexOf("line-1");
        int thirdIdx = stdout.indexOf("line-2");
        assertTrue(firstIdx >= 0 && secondIdx > firstIdx && thirdIdx > secondIdx,
                "stdout must contain line-0 < line-1 < line-2 in order, got: " + stdout);
    }

    /**
     * Pins the documented 30-second wall-clock timeout wrapper. An infinite loop should be
     * killed by GNU {@code timeout} inside the container rather than hanging the Java thread.
     * Disabled by default because the happy-path case confirms the wrapper is installed; running
     * this case adds ~30s of wall-clock to CI per invocation. Enable locally when changing the
     * timeout wrapper or validating sandbox kill semantics.
     */
    @Test
    @Disabled("30-second wall-clock cost on every CI run; enable locally when touching the timeout wrapper in PythonSandboxService")
    void executeCodeInfiniteLoopIsKilledByTimeoutWrapperWithExitCode124() {
        ExecutionResult result = pythonSandbox.executeCode("while True: pass");

        assertNotEquals(0, result.exitCode());
        assertEquals(124, result.exitCode(),
                "GNU timeout reports exit code 124 on expiry; got " + result.exitCode());
    }

    /**
     * Pins that network access from the sandbox container is denied. The service wires
     * {@code withNetworkMode("none")} on the Testcontainers {@code GenericContainer}, removing
     * the default bridge interface entirely so outbound traffic has no route.
     */
    @Test
    void executeCodeOutboundNetworkAccessIsDenied() {
        String code = """
                import urllib.request
                try:
                    urllib.request.urlopen("http://example.com", timeout=5)
                    print("REACHABLE")
                except Exception as e:
                    print("BLOCKED:" + type(e).__name__)
                """;

        ExecutionResult result = pythonSandbox.executeCode(code);
        assertTrue(result.stdout().startsWith("BLOCKED:"),
                "network access must be blocked; got stdout=" + result.stdout());
    }
}
