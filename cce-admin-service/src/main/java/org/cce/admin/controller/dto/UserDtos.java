package org.cce.admin.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.UserStatus;

/** The User shape of section 4.4 (AS-REQ-04). Phase 6 adds the request records. */
public final class UserDtos {

    private UserDtos() {
    }

    /** The same shape is used in lists, in the detail view and in audit JSON. */
    public record User(
            UUID id,
            UUID keycloakId,
            String username,
            String email,
            String firstName,
            String lastName,
            UserStatus status,
            String facilityScope,
            List<UserRole> roles,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** An item of {@code User.roles}, ordered by {@code name}. */
    public record UserRole(UUID id, String name, String displayName) {
    }
}
