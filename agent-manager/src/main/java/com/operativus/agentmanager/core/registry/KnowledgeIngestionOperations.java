package com.operativus.agentmanager.core.registry;

import org.springframework.core.io.Resource;

public interface KnowledgeIngestionOperations {
    void ingest(Resource resource);
    void ingestUrl(String url);
}
