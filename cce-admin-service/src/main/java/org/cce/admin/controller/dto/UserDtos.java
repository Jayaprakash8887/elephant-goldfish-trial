package org.cce.admin.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.UserStatus;

/**
 * The User shape of section 4.4 (AS-REQ-04), {@code ResetPasswordResult}, and the four user request
 * records. Request strings are trimmed before validation, and an empty one is null (section 4.1).
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** The username rule of section 4.4. */
    public static final String USERNAME_PATTERN = "^[a-z0-9][a-z0-9._-]*$";

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

    /** {@code { userId, email }}: the address the set-password email was sent to (AS-REQ-19). */
    public record ResetPasswordResult(UUID userId, String email) {
    }

    /**
     * {@code POST /users}. {@code roleIds} has 1 to 20 distinct ids of existing roles; that they are
     * distinct and exist is checked by the service (5.1 step 1).
     */
    public record CreateUserRequest(
            @NotNull @Size(min = 3, max = 50) @Pattern(regexp = USERNAME_PATTERN) String username,
            @NotNull @Size(max = 254) @Email String email,
            @NotNull @Size(min = 1, max = 100) String firstName,
            @NotNull @Size(min = 1, max = 100) String lastName,
            @Size(max = 50) String facilityScope,
            @NotNull @Size(min = 1, max = 20) List<@NotNull UUID> roleIds) {
    }

    /** {@code PUT /users/:id}. All four are replaced, so a missing or null {@code facilityScope} clears it. */
    public record UpdateUserRequest(
            @NotNull @Size(max = 254) @Email String email,
            @NotNull @Size(min = 1, max = 100) String firstName,
            @NotNull @Size(min = 1, max = 100) String lastName,
            @Size(max = 50) String facilityScope) {
    }

    /** {@code PATCH /users/:id/status}. A value outside the three statuses is a 400. */
    public record ChangeStatusRequest(@NotNull UserStatus status) {
    }

    /** {@code PUT /users/:id/roles}: 0 to 20 distinct ids of existing roles, the complete new set (5.4). */
    public record ReplaceUserRolesRequest(@NotNull @Size(max = 20) List<@NotNull UUID> roleIds) {
    }
}
