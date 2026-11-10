package org.cce.admin.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.CommonDtos.ApiResponse;
import org.cce.admin.controller.dto.MenuDtos.CreateMenuRequest;
import org.cce.admin.controller.dto.MenuDtos.Menu;
import org.cce.admin.controller.dto.MenuDtos.UpdateMenuRequest;
import org.cce.admin.service.MenuService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The four {@code /menus} endpoints of section 4.5 (B-44). Shapes HTTP only. */
@RestController
@RequestMapping("/admin/v1/menus")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    public ApiResponse<List<Menu>> tree() {
        return ApiResponse.of(menuService.tree());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Menu>> create(@Valid @RequestBody CreateMenuRequest request) {
        Menu menu = menuService.create(request);
        return ResponseEntity.created(URI.create("/admin/v1/menus/" + menu.id())).body(ApiResponse.of(menu));
    }

    @PutMapping("/{id}")
    public ApiResponse<Menu> update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateMenuRequest request) {
        return ApiResponse.of(menuService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        menuService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
