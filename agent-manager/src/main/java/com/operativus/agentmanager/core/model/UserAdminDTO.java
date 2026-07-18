package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.model.enums.RoleType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UserAdminDTO(
        UUID id,
        String username,
        String email,
        Set<RoleType> roles,
        boolean disabled,
        LocalDateTime lastLoginAt
) {
    public record CreateRequest(
            String username,
            String email,
            String password,
            Set<RoleType> roles
    ) {}

    public record UpdateRequest(
            String email,
            Set<RoleType> roles,
            Boolean disabled
    ) {}

    public record ResetPasswordRequest(
            String password
    ) {}

    public record BulkCreateRequest(
            List<CreateRequest> users
    ) {}

    public record BulkCreateItem(
            UUID id,
            String username,
            String email,
            String status
    ) {
        public static final String STATUS_CREATED = "created";
        public static final String STATUS_ALREADY_EXISTS = "already_exists";
    }

    public record BulkCreateResponse(
            List<BulkCreateItem> items,
            int created,
            int alreadyExisted
    ) {}
}
