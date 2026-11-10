package org.cce.admin.controller;

import java.util.List;
import org.cce.admin.controller.dto.CommonDtos.ApiResponse;
import org.cce.admin.controller.dto.MeDtos.Me;
import org.cce.admin.controller.dto.MeDtos.MenuTreeItem;
import org.cce.admin.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /admin/v1/me} (B-20) and {@code GET /admin/v1/me/menus} (B-21). */
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

    @GetMapping("/admin/v1/me/menus")
    public ApiResponse<List<MenuTreeItem>> menus() {
        return ApiResponse.of(currentUserService.menus());
    }
}
