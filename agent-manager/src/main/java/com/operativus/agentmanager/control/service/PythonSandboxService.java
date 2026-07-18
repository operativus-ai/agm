package com.operativus.agentmanager.control.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import com.operativus.agentmanager.core.exception.BusinessValidationException;

/**
 * Domain Responsibility: Provides a secured, ephemeral execution environment for running AI-generated Python code.
 * State: Stateless
 */
@Service
public class PythonSandboxService implements com.operativus.agentmanager.core.registry.PythonSandboxOperations {

    private static final Logger log = LoggerFactory.getLogger(PythonSandboxService.class);
    private static final String PYTHON_IMAGE = "python:3.11-slim";

    /**
     * @summary Executes the given Python code in a secure, isolated Docker container.
     * @logic Validates the input string is not empty, provisions an ephemeral 'python:3.11-slim' Testcontainer image with {@code --network=none} so no bridge interface is attached, starts the container, copies the target code into the container filesystem, executes the script with a strict 30-second OS-level timeout wrapper, and captures the standard output, error, and exit codes. Containment relies on (1) network isolation, (2) ephemeral container lifecycle (no persisted state), and (3) the 30-second timeout — no source-level filtering, since substring blocklists on Python imports are trivially bypassable (e.g. {@code __import__("os")}, {@code from os import path}, {@code importlib.import_module("os")}) and provide false security on top of the real defenses.
     */
    @Override
    public ExecutionResult executeCode(String code) {
        log.info("Preparing to execute Python code in sandbox...");

        // Basic syntax validation boundary
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessValidationException("Cannot execute empty Python code.");
        }

        try (GenericContainer<?> pythonContainer = new GenericContainer<>(PYTHON_IMAGE)) {
            pythonContainer.withCommand("python", "/tmp/script.py");
            pythonContainer.withCopyToContainer(Transferable.of(code), "/tmp/script.py");
            
            // We want the container to start, run the command, and exit.
            // However, GenericContainer usually expects a long-running service.
            // For one-off scripts, we trigger start which runs the command.
            // But standard start() waits for listening ports etc.
            // A better pattern for one-off tasks with Testcontainers is often just running it.
            // Since we are using "python script.py", it will exit immediately after run.
            // We need to configure it to wait for the command to finish.
            
            // Actually, the cleanest way for a script is to start a container that sleeps,
            // then use execInContainer. But that requires a running container.
            // Let's try the exec approach which is faster if we reuse containers (Phase 6 optimization).
            // For Phase 5 "On Demand", we can just run the container.
            
            // Let's use the 'Exec' pattern on a running container that stays alive for the duration of the request?
            // No, the safest "Sandbox" is ephemeral. Start -> Run -> Die.
            // But GenericContainer start() throws if container exits immediately (which it will).
            // So we use a "tail -f /dev/null" entrypoint to keep it alive, then exec, then stop.
            
            pythonContainer.withCommand("tail", "-f", "/dev/null");
            pythonContainer.withNetworkMode("none");
            pythonContainer.start();
            
            // Copy script
            pythonContainer.copyFileToContainer(Transferable.of(code), "/tmp/script.py");
            
            // Execute with a strict 30-second OS-level boundary timeout using 'timeout'
            org.testcontainers.containers.Container.ExecResult result = pythonContainer.execInContainer("timeout", "30s", "python", "/tmp/script.py");
            
            return new ExecutionResult(result.getStdout(), result.getStderr(), result.getExitCode());
            
        } catch (Exception e) {
            log.error("Failed to execute Python code", e);
            return new ExecutionResult("", "Execution failed: " + e.getMessage(), -1);
        }
    }
}
