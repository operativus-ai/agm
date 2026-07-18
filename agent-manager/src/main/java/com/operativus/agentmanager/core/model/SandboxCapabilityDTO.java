package com.operativus.agentmanager.core.model;

import java.util.List;

public record SandboxCapabilityDTO(
    String agentId,
    String threadId,
    List<String> activeCapabilities,
    List<String> restrictedPaths,
    String memoryIsolation
) {}
