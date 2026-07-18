package com.operativus.agentmanager.core.model;

public final class MetadataKeys {

    private MetadataKeys() {}

    public static final String SOURCE_ID = "sourceId";
    public static final String ORG_ID = "orgId";
    public static final String TIMESTAMP = "timestamp";
    public static final String SYSTEM_CLASSIFICATION = "systemClassification";
    public static final String KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String SOURCE_URL = "sourceUrl";

    /**
     * Discriminates which logical store owns a row in the shared {@code vector_store}
     * table. MemoryService writes {@link #STORE_TYPE_MEMORY}; KnowledgeService writes
     * {@link #STORE_TYPE_KB}. Both services AND this predicate into their search
     * filters so memory searches no longer surface KB chunks (and vice versa).
     */
    public static final String STORE_TYPE = "storeType";
    public static final String STORE_TYPE_MEMORY = "MEMORY";
    public static final String STORE_TYPE_KB = "KB";
}
