package com.operativus.agentmanager.core.exception;

/**
 * Domain Responsibility: Defines a custom runtime exception for errors occurring during the processing or storage of RAG knowledge base items.
 * State: Stateless (Exception carrier)
 */
public class KnowledgeProcessingException extends RuntimeException {
    public KnowledgeProcessingException(String message) {
        super(message);
    }

    public KnowledgeProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
