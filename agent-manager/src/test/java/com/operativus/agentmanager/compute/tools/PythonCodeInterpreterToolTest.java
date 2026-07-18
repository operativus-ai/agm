package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.core.registry.PythonSandboxOperations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Domain Responsibility: Runtime test for {@link PythonCodeInterpreterTool}. Asserts the
 * 5 vectors per docs/plans/agm-tools-impl.md §3 (Phase 0 verdicts captured verbatim from
 * source):
 *   (a) success path returns sandbox stdout unchanged
 *   (b) empty-stdout success returns canonical "Code executed successfully but returned no output." string
 *   (c) exitCode != 0 returns formatted "Execution Failed (Exit Code N):" with stderr verbatim
 *   (d) markdown ```python fence stripping — sandbox sees clean code
 *   (e) bare ``` fence stripping — sandbox sees clean code
 *
 * State: Stateless. Mockito stub of PythonSandboxOperations provides independent ground truth
 * (A18) — production formats whatever the stub returns; assertions read the formatted output.
 */
class PythonCodeInterpreterToolTest {

    private final PythonSandboxOperations sandbox = mock(PythonSandboxOperations.class);
    private final PythonCodeInterpreterTool tool = new PythonCodeInterpreterTool(sandbox);

    private static PythonSandboxOperations.ExecutionResult success(String stdout) {
        return new PythonSandboxOperations.ExecutionResult(stdout, "", 0);
    }

    private static PythonSandboxOperations.ExecutionResult failure(int exit, String stderr, String stdout) {
        return new PythonSandboxOperations.ExecutionResult(stdout, stderr, exit);
    }

    // (a) success path
    @Test
    void successPath_returnsSandboxStdout() {
        when(sandbox.executeCode("print(42)")).thenReturn(success("42\n"));

        String result = tool.run_python("print(42)");

        assertEquals("42\n", result);
    }

    // (b) empty stdout fallback
    @Test
    void emptyStdout_returnsCanonicalFallback() {
        when(sandbox.executeCode("x = 1")).thenReturn(success(""));

        String result = tool.run_python("x = 1");

        assertEquals("Code executed successfully but returned no output.", result);
    }

    // (c) exitCode != 0 returns formatted failure
    @Test
    void nonZeroExitCode_returnsFormattedFailure() {
        when(sandbox.executeCode("undefined_name")).thenReturn(failure(1, "NameError: name 'undefined_name' is not defined", ""));

        String result = tool.run_python("undefined_name");

        assertTrue(result.startsWith("Execution Failed (Exit Code 1):"),
                "expected failure prefix, got: " + result);
        assertTrue(result.contains("NameError: name 'undefined_name' is not defined"),
                "expected stderr verbatim, got: " + result);
    }

    // (d) markdown ```python fence stripping — sandbox MUST see code without fence
    @Test
    void markdownPythonFence_isStrippedBeforeSandbox() {
        when(sandbox.executeCode("print(1)")).thenReturn(success("1\n"));

        String result = tool.run_python("```python\nprint(1)\n```");

        assertEquals("1\n", result);
        verify(sandbox).executeCode(eq("print(1)"));
    }

    // (e) bare ``` fence stripping — sandbox MUST see code without fence
    @Test
    void bareTripleBacktick_isStrippedBeforeSandbox() {
        when(sandbox.executeCode("print(2)")).thenReturn(success("2\n"));

        String result = tool.run_python("```\nprint(2)\n```");

        assertEquals("2\n", result);
        verify(sandbox).executeCode(eq("print(2)"));
    }
}
