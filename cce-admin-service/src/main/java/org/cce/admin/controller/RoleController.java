package org.cce.admin.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.CommonDtos.ApiResponse;
import org.cce.admin.controller.dto.RoleDtos.CreateRoleRequest;
import org.cce.admin.controller.dto.RoleDtos.ReplaceMenusRequest;
import org.cce.admin.controller.dto.RoleDtos.ReplacePermissionsRequest;
import org.cce.admin.controller.dto.RoleDtos.Role;
import org.cce.admin.controller.dto.RoleDtos.RoleMenus;
import org.cce.admin.controller.dto.RoleDtos.RolePermissions;
import org.cce.admin.controller.dto.RoleDtos.RoleSyncResult;
import org.cce.admin.controller.dto.RoleDtos.UpdateRoleRequest;
import org.cce.admin.service.RoleMenuService;
import org.cce.admin.service.RolePermissionService;
import org.cce.admin.service.RoleService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The ten {@code /roles} endpoints of section 4.5 (B-40 to B-43). Shapes HTTP only. */
@RestController
@RequestMapping("/admin/v1/roles")
public class RoleController {

    private final RoleService roleService;
    private final RoleMenuService roleMenuService;
    private final RolePermissionService rolePermissionService;

    public RoleController(RoleService roleService, RoleMenuService roleMenuService,
            RolePermissionService rolePermissionService) {
        this.roleService = roleService;
        this.roleMenuService = roleMenuService;
        this.rolePermissionService = rolePermissionService;
    }

    @GetMapping
    public ApiResponse<List<Role>> list(@Valid @ParameterObject PageParams page) {
        RoleService.RolePage result = roleService.list(page.toPageRequest(RoleService.SORT));
        return ApiResponse.of(result.items(), page.toPagination(result.totalItems()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Role>> create(@Valid @RequestBody CreateRoleRequest request) {
        RoleService.RoleCreated created = roleService.create(request);
        return ResponseEntity.created(URI.create("/admin/v1/roles/" + created.role().id()))
                .body(ApiResponse.withWarnings(created.role(), created.warnings()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Role> get(@PathVariable("id") UUID id) {
        return ApiResponse.of(roleService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Role> update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.of(roleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        roleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/menus")
    public ApiResponse<RoleMenus> menus(@PathVariable("id") UUID id) {
        return ApiResponse.of(roleMenuService.get(id));
    }

    @PutMapping("/{id}/menus")
    public ApiResponse<RoleMenus> replaceMenus(@PathVariable("id") UUID id,
            @Valid @RequestBody ReplaceMenusRequest request) {
        RoleMenuService.RoleMenusResult result = roleMenuService.replace(id, request);
        return ApiResponse.withWarnings(result.menus(), result.warnings());
    }

    @GetMapping("/{id}/permissions")
    public ApiResponse<RolePermissions> permissions(@PathVariable("id") UUID id) {
        return ApiResponse.of(rolePermissionService.get(id));
    }

    @PutMapping("/{id}/permissions")
    public ApiResponse<RolePermissions> replacePermissions(@PathVariable("id") UUID id,
            @Valid @RequestBody ReplacePermissionsRequest request) {
        return ApiResponse.of(rolePermissionService.replace(id, request));
    }

    @PostMapping("/{id}/sync")
    public ApiResponse<RoleSyncResult> sync(@PathVariable("id") UUID id) {
        return ApiResponse.of(rolePermissionService.sync(id));
    }
}
