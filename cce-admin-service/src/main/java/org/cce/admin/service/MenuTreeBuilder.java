package org.cce.admin.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.cce.admin.controller.dto.MeDtos.MenuTreeItem;
import org.cce.admin.entity.MenuEntity;
import org.cce.admin.entity.MenuType;

/**
 * Builds the {@code GET /me/menus} tree from the union of the active menus granted to the caller's
 * roles (section 4.5, rules for {@code GET /me/menus}, B-21). The input is taken as given: the
 * caller passes only active granted menus.
 *
 * <ul>
 *   <li>{@code NAV} menus are at the top level.</li>
 *   <li>A {@code DETAIL} menu goes under its parent only when the parent is in the input too. A
 *       {@code DETAIL} whose parent is not granted, or not active, is left out (DEV-10).</li>
 *   <li>Siblings are ordered by {@code sortOrder}, then {@code label}.</li>
 * </ul>
 */
public final class MenuTreeBuilder {

    private static final Comparator<MenuEntity> SIBLING_ORDER =
            Comparator.comparingInt(MenuEntity::getSortOrder).thenComparing(MenuEntity::getLabel);

    private MenuTreeBuilder() {
    }

    /** The tree of the given menus; an empty input gives an empty list. */
    public static List<MenuTreeItem> build(Collection<MenuEntity> menus) {
        // The union: one entry per menu id, whatever roles granted it more than once.
        Map<UUID, MenuEntity> byId = new LinkedHashMap<>();
        for (MenuEntity menu : menus) {
            byId.putIfAbsent(menu.getId(), menu);
        }
        return byId.values().stream()
                .filter(menu -> menu.getType() == MenuType.NAV)
                .sorted(SIBLING_ORDER)
                .map(nav -> new MenuTreeItem(nav.getId(), nav.getKey(), nav.getLabel(), nav.getPath(), nav.getIcon(),
                        nav.getType(), nav.getSortOrder(), children(nav, byId.values())))
                .toList();
    }

    private static List<MenuTreeItem> children(MenuEntity parent, Collection<MenuEntity> menus) {
        return menus.stream()
                .filter(menu -> menu.getType() == MenuType.DETAIL && parent.getId().equals(menu.getParentId()))
                .sorted(SIBLING_ORDER)
                .map(detail -> new MenuTreeItem(detail.getId(), detail.getKey(), detail.getLabel(), detail.getPath(),
                        detail.getIcon(), detail.getType(), detail.getSortOrder(), List.of()))
                .toList();
    }
}
