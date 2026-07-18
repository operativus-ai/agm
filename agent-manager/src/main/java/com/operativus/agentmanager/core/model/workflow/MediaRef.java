package com.operativus.agentmanager.core.model.workflow;

/**
 * Domain Responsibility: A JSON-serializable reference to a media artifact (image / file)
 *     threaded through a DAG workflow's {@link StepInput}/{@link StepOutput} (DAG plan §2.2).
 *     Deliberately a thin {@code (mimeType, url)} pair — NOT Spring AI's {@code Media} (which
 *     carries a live {@code Resource} and is not persistence-friendly). Conversion to a
 *     fetchable {@code Media} for an actual agent run goes through the SSRF-guarded media
 *     fetch path, never directly from this ref.
 * State: Stateless (Immutable Record carrier)
 */
public record MediaRef(String mimeType, String url) {}
