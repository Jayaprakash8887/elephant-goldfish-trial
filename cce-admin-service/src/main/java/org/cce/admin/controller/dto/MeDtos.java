package org.cce.admin.controller.dto;

import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.MenuType;

/** The Me and menu tree item shapes of section 4.4. */
public final class MeDtos {

    private MeDtos() {
    }

    /** {@code roles}: the caller's token roles that exist in {@code roles}, ordered by name. */
    public record Me(UUID id, String username, List<String> roles, String facilityScope) {
    }

    /** An item of {@code GET /me/menus} (Phase 7). */
    public record MenuTreeItem(
            UUID id,
            String key,
            String label,
            String path,
            String icon,
            MenuType type,
            int sortOrder,
            List<MenuTreeItem> children) {
    }
}
