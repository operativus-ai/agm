package com.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Domain Responsibility: Grouping namespace class for Authentication and Authorization Data Transfer Objects (Login, Registration, JWT Responses).
 * State: Stateless (DTO container)
 */
public class AuthModels {
    
    public record LoginRequest(String username, String password) {}
    
    public record RegisterRequest(String username, String email, String password, List<String> role) {}
    
    public record JwtResponse(String token, String type, String id, String username, String email, List<String> roles) {
        public JwtResponse(String token, String id, String username, String email, List<String> roles) {
            this(token, "Bearer", id, username, email, roles);
        }
    }
    
    public record MessageResponse(String message) {}
}
