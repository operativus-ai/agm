package ai.operativus.agentmanager.core.exception;

/**
 * Domain Responsibility: Defines a custom runtime exception resulting from optimistic locking failures when concurrent edits to an entity are detected.
 * State: Stateless (Exception carrier)
 */
public class StaleDataException extends RuntimeException {

    private final String entityName;
    private final String identifier;

    public StaleDataException(String entityName, String identifier) {
        super(String.format("The %s with identifier '%s' was modified concurrently by another user.", entityName, identifier));
        this.entityName = entityName;
        this.identifier = identifier;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getIdentifier() {
        return identifier;
    }
}
