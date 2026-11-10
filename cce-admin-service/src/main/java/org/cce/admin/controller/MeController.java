package org.cce.admin.controller;

import org.cce.admin.controller.dto.CommonDtos.ApiResponse;
import org.cce.admin.controller.dto.MeDtos.Me;
import org.cce.admin.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /admin/v1/me} (B-20). */
@RestController
public class MeController {

    private final CurrentUserService currentUserService;

    public MeController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/admin/v1/me")
    public ApiResponse<Me> me() {
        return ApiResponse.of(currentUserService.me());
    }
}
