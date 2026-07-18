package com.operativus.agentmanager.control.controller.model;

import java.util.List;

/**
 * Domain Responsibility: Response payload for batch file upload — accepted document IDs and rejected file reasons.
 * State: Immutable value object
 */
public record UploadBatchResponse(List<String> accepted, List<String> rejected) {}
