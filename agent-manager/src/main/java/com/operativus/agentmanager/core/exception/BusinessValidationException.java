package com.operativus.agentmanager.core.exception;

import java.util.List;

/**
 * Domain Responsibility: Defines a custom runtime exception for reporting domain-specific business rule violations.
 * State: Stateless (Exception carrier)
 */
public class BusinessValidationException extends RuntimeException {

    private final List<String> errors;

    public BusinessValidationException(String message) {
        super(message);
        this.errors = List.of(message);
    }
    
    public BusinessValidationException(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
