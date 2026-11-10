package org.cce.admin.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.CommonDtos.ApiResponse;
import org.cce.admin.controller.dto.UserDtos.ChangeStatusRequest;
import org.cce.admin.controller.dto.UserDtos.CreateUserRequest;
import org.cce.admin.controller.dto.UserDtos.ReplaceUserRolesRequest;
import org.cce.admin.controller.dto.UserDtos.ResetPasswordResult;
import org.cce.admin.controller.dto.UserDtos.UpdateUserRequest;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.service.UserService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The seven {@code /users} endpoints of section 4.5 (B-30 to B-36). Shapes HTTP only. */
@RestController
@RequestMapping("/admin/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Filters must all match; an empty {@code q} is ignored, and unknown parameters too (section 4.5). */
    @GetMapping
    public ApiResponse<List<User>> list(
            @RequestParam(name = "status", required = false) UserStatus status,
            @RequestParam(name = "roleId", required = false) UUID roleId,
            @RequestParam(name = "facilityScope", required = false) String facilityScope,
            @RequestParam(name = "q", required = false) @Size(max = 100) String q,
            @Valid @ParameterObject PageParams page) {
        UserService.UserPage result = userService.list(status, roleId, facilityScope, q,
                page.toPageRequest(UserService.SORT));
        return ApiResponse.of(result.items(), page.toPagination(result.totalItems()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<User>> create(@Valid @RequestBody CreateUserRequest request) {
        UserService.UserCreated created = userService.create(request);
        return ResponseEntity.created(URI.create("/admin/v1/users/" + created.user().id()))
                .body(ApiResponse.withWarnings(created.user(), created.warnings()));
    }

    @GetMapping("/{id}")
    public ApiResponse<User> get(@PathVariable("id") UUID id) {
        return ApiResponse.of(userService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<User> update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.of(userService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<User> changeStatus(@PathVariable("id") UUID id,
            @Valid @RequestBody ChangeStatusRequest request) {
        return ApiResponse.of(userService.changeStatus(id, request));
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<User> replaceRoles(@PathVariable("id") UUID id,
            @Valid @RequestBody ReplaceUserRolesRequest request) {
        return ApiResponse.of(userService.replaceRoles(id, request));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<ResetPasswordResult> resetPassword(@PathVariable("id") UUID id) {
        return ApiResponse.of(userService.resetPassword(id));
    }
}
