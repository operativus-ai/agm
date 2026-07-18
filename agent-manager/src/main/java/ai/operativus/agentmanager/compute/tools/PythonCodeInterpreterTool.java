package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.core.registry.PythonSandboxOperations;

import ai.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Domain Responsibility: Provides Spring AI tools to execute raw Python code inside an ephemeral sandbox container.
 * State: Stateless
 */
@AgentToolComponent
public class PythonCodeInterpreterTool {

    private final PythonSandboxOperations pythonSandboxService;

    public PythonCodeInterpreterTool(PythonSandboxOperations pythonSandboxService) {
        this.pythonSandboxService = pythonSandboxService;
    }

    /**
     * @summary Executes Python code safely in a sandbox environment and retrieves the combined standard output and error.
     * @logic Strips injected markdown block syntax from the provided payload and delegates raw execution to the PythonSandboxOperations service.
     */
    @RequiresCapability("code_execution")
    @Tool(description = "Executes Python code in a secure sandbox. Use this tool for math, data analysis, or logic tasks. Returns the standard output and error.")
    public String run_python(
        @ToolParam(description = "The Python code to execute. Do not wrap in markdown blocks.") String code
    ) {
        // Strip markdown code blocks if present (agents often wrap code in ```python)
        String cleanedCode = code.replaceAll("^```python\\s*", "").replaceAll("^```\\s*", "").replaceAll("```\\s*$", "").trim();
        
        PythonSandboxOperations.ExecutionResult result = pythonSandboxService.executeCode(cleanedCode);

        if (result.exitCode() != 0) {
            return "Execution Failed (Exit Code " + result.exitCode() + "):\nSTDERR:\n" + result.stderr() + "\nSTDOUT:\n" + result.stdout();
        }
        return result.stdout().isEmpty() ? "Code executed successfully but returned no output." : result.stdout();
    }
}
