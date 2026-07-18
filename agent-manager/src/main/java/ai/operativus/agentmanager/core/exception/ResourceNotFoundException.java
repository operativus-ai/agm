package ai.operativus.agentmanager.core.exception;

/**
 * Domain Responsibility: Defines a custom runtime exception for cases where a requested database entity or file resource cannot be found.
 * State: Stateless (Exception carrier)
 */
public class ResourceNotFoundException extends RuntimeException {
    
    private final String resourceName;
    private final String identifier;

    public ResourceNotFoundException(String resourceName, String identifier) {
        super(String.format("%s not found with identifier: '%s'", resourceName, identifier));
        this.resourceName = resourceName;
        this.identifier = identifier;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getIdentifier() {
        return identifier;
    }
}
