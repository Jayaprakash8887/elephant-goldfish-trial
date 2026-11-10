package org.cce.admin.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.SyncStatus;

/**
 * The Role and Permission row shapes of section 4.4, the named result records {@code RoleMenus},
 * {@code RolePermissions} and {@code RoleSyncResult}, and the role request records. Request strings
 * are trimmed before validation, and an empty one is null (section 4.1).
 */
public final class RoleDtos {

    private RoleDtos() {
    }

    /** The role name rule of section 4.4, also the CHECK on {@code roles.name}. */
    public static final String ROLE_NAME_PATTERN = "^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$";

    /** Role (AS-REQ-11). Used in lists, in the detail view and in audit JSON. */
    public record Role(
            UUID id,
            String name,
            String displayName,
            String description,
            @JsonProperty("isSystem") boolean isSystem,
            @JsonProperty("isAssignable") boolean isAssignable,
            SyncStatus syncStatus,
            Instant lastSyncAt,
            long userCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** Permission row (AS-REQ-17). {@code id} and {@code implicit} are response only. */
    public record PermissionRow(
            Long id,
            String httpMethod,
            String uriPattern,
            String description,
            String resourceCategory,
            boolean implicit) {
    }

    /** {@code { roleId, menuIds }} (AS-REQ-16). */
    public record RoleMenus(UUID roleId, List<UUID> menuIds) {
    }

    /** {@code { roleId, syncStatus, lastSyncAt, permissions }}: implicit rows first (section 4.5). */
    public record RolePermissions(
            UUID roleId,
            SyncStatus syncStatus,
            Instant lastSyncAt,
            List<PermissionRow> permissions) {
    }

    /** {@code { roleId, syncStatus, lastSyncAt }} (AS-REQ-19). */
    public record RoleSyncResult(UUID roleId, SyncStatus syncStatus, Instant lastSyncAt) {
    }

    /** {@code POST /roles}: {@code { name, displayName, description? }}. */
    public record CreateRoleRequest(
            @NotNull @Size(min = 2, max = 100) @Pattern(regexp = ROLE_NAME_PATTERN) String name,
            @NotNull @Size(min = 1, max = 100) String displayName,
            @Size(max = 500) String description) {
    }

    /** {@code PUT /roles/:id}: {@code { displayName, description }}. {@code name} is an unknown property. */
    public record UpdateRoleRequest(
            @NotNull @Size(min = 1, max = 100) String displayName,
            @Size(max = 500) String description) {
    }

    /** {@code PUT /roles/:id/menus}: 0 to 500 distinct ids of existing menus (5.11). */
    public record ReplaceMenusRequest(@NotNull @Size(max = 500) List<@NotNull UUID> menuIds) {
    }

    /**
     * {@code PUT /roles/:id/permissions}: 0 to 200 rows, the role's complete set of explicit rows
     * (5.7). The method list and the {@code uriPattern} rules are checked by
     * {@code PermissionRowRules}.
     */
    public record ReplacePermissionsRequest(
            @NotNull @Size(max = 200) List<@NotNull @Valid PermissionRowInput> permissions) {
    }

    /** One requested row: {@code { httpMethod, uriPattern, description?, resourceCategory? }}. */
    public record PermissionRowInput(
            @NotNull String httpMethod,
            @NotNull @Size(min = 1, max = 255) String uriPattern,
            @Size(max = 1000) String description,
            @Size(max = 50) String resourceCategory) {
    }
}
