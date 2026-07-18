package com.operativus.agentmanager.core.registry;

public interface PythonSandboxOperations {
    record ExecutionResult(String stdout, String stderr, int exitCode) {}
    
    ExecutionResult executeCode(String code);
}
