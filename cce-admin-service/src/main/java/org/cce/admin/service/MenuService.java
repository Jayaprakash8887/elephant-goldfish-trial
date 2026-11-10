package org.cce.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.cce.admin.audit.AuditAction;
import org.cce.admin.audit.AuditEntry;
import org.cce.admin.audit.AuditWriter;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.MenuDtos.CreateMenuRequest;
import org.cce.admin.controller.dto.MenuDtos.Menu;
import org.cce.admin.controller.dto.MenuDtos.UpdateMenuRequest;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.MenuEntity;
import org.cce.admin.entity.MenuType;
import org.cce.admin.repository.MenuRepository;
import org.cce.admin.repository.RoleMenuRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The menu catalog (B-44): the tree for {@code GET /menus}, and create, update and delete (5.12),
 * each one database transaction with its audit row. No Keycloak or gateway call. No class- or
 * method-level {@code @Transactional}.
 */
@Service
public class MenuService {

    /** Siblings are ordered by {@code sortOrder}, then {@code label} (section 4.4). */
    public static final Sort SORT = Sort.by("sortOrder", "label");

    private static final Comparator<MenuEntity> SIBLING_ORDER =
            Comparator.comparingInt(MenuEntity::getSortOrder).thenComparing(MenuEntity::getLabel);

    private final MenuRepository menuRepository;
    private final RoleMenuRepository roleMenuRepository;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public MenuService(MenuRepository menuRepository, RoleMenuRepository roleMenuRepository, AuditWriter auditWriter,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.menuRepository = menuRepository;
        this.roleMenuRepository = roleMenuRepository;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** {@code GET /menus}: the top-level menus with their children, inactive menus included. */
    public List<Menu> tree() {
        List<MenuEntity> all = menuRepository.findAll(SORT);
        return all.stream()
                .filter(menu -> menu.getParentId() == null)
                .map(menu -> toMenu(menu, all))
                .toList();
    }

    /** {@code POST /menus} (5.12): writes {@code MENU_CREATED}. */
    public Menu create(CreateMenuRequest request) {
        UUID id = transaction.execute(status -> {
            checkParent(request.type(), request.parentId(), null);
            if (menuRepository.existsByKey(request.key())) {
                throw new ApiException(ErrorCode.DUPLICATE_MENU_KEY, "A menu with the key " + request.key() + " exists.");
            }
            MenuEntity menu = new MenuEntity();
            menu.setKey(request.key());
            menu.setLabel(request.label());
            menu.setPath(request.path());
            menu.setIcon(request.icon());
            menu.setType(request.type());
            menu.setParentId(request.parentId());
            menu.setSortOrder(request.sortOrder());
            menu.setActive(request.isActive() == null || request.isActive());
            MenuEntity saved = menuRepository.saveAndFlush(menu);
            auditWriter.record(AuditEntry.success(AuditAction.MENU_CREATED, AuditEntityType.MENU, saved.getId(),
                    null, view(saved)));
            return saved.getId();
        });
        return get(id);
    }

    /** {@code PUT /menus/:id} (5.12): writes {@code MENU_UPDATED}, or nothing on a no-op. */
    public Menu update(UUID id, UpdateMenuRequest request) {
        transaction.executeWithoutResult(status -> {
            MenuEntity menu = load(id);
            checkParent(menu.getType(), request.parentId(), id);
            if (Objects.equals(menu.getLabel(), request.label())
                    && Objects.equals(menu.getPath(), request.path())
                    && Objects.equals(menu.getIcon(), request.icon())
                    && Objects.equals(menu.getParentId(), request.parentId())
                    && menu.getSortOrder() == request.sortOrder()
                    && menu.isActive() == request.isActive()) {
                return; // No-op: nothing is written or audited.
            }
            Menu before = view(menu);
            menu.setLabel(request.label());
            menu.setPath(request.path());
            menu.setIcon(request.icon());
            menu.setParentId(request.parentId());
            menu.setSortOrder(request.sortOrder());
            menu.setActive(request.isActive());
            MenuEntity saved = menuRepository.saveAndFlush(menu);
            auditWriter.record(AuditEntry.success(AuditAction.MENU_UPDATED, AuditEntityType.MENU, id, before,
                    view(saved)));
        });
        return get(id);
    }

    /** {@code DELETE /menus/:id} (5.12, B-44): also deletes the menu's {@code role_menus} rows. */
    public void delete(UUID id) {
        transaction.executeWithoutResult(status -> {
            MenuEntity menu = load(id);
            if (menuRepository.existsByParentId(id)) {
                throw new ApiException(ErrorCode.MENU_HAS_CHILDREN,
                        "The menu still has child menus. Delete or move them first.");
            }
            List<UUID> roleIds = RoleMenuService.sortedIds(roleMenuRepository.findRoleIdsByMenuId(id));
            ObjectNode before = objectMapper.valueToTree(view(menu));
            before.set("roleIds", objectMapper.valueToTree(roleIds));
            roleMenuRepository.deleteByMenuId(id);
            menuRepository.delete(menu);
            menuRepository.flush();
            auditWriter.record(AuditEntry.success(AuditAction.MENU_DELETED, AuditEntityType.MENU, id, before, null));
        });
    }

    private Menu get(UUID id) {
        return view(load(id));
    }

    private MenuEntity load(UUID id) {
        return menuRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.MENU_NOT_FOUND, "No menu has this id."));
    }

    /**
     * The parent rules of section 4.4: a {@code NAV} menu has no parent, and a {@code DETAIL} menu
     * has an existing {@code NAV} parent. A break is a 400 on {@code parentId}.
     */
    private void checkParent(MenuType type, UUID parentId, UUID selfId) {
        String problem = null;
        if (type == MenuType.NAV && parentId != null) {
            problem = "A NAV menu has no parent.";
        } else if (type == MenuType.DETAIL) {
            if (parentId == null) {
                problem = "A DETAIL menu must have a NAV parent.";
            } else if (parentId.equals(selfId)) {
                problem = "A menu cannot be its own parent.";
            } else {
                MenuEntity parent = menuRepository.findById(parentId).orElse(null);
                if (parent == null) {
                    problem = "No menu has this id.";
                } else if (parent.getType() != MenuType.NAV) {
                    problem = "The parent of a DETAIL menu must be a NAV menu.";
                }
            }
        }
        if (problem != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The request is not valid.",
                    List.of(new FieldErrorDto("parentId", problem)));
        }
    }

    /** The menu with its children read from the database. */
    private Menu view(MenuEntity menu) {
        List<MenuEntity> children = menu.getType() == MenuType.NAV
                ? menuRepository.findAll(SORT).stream().filter(m -> menu.getId().equals(m.getParentId())).toList()
                : List.of();
        List<MenuEntity> family = new ArrayList<>(children);
        family.add(menu);
        return toMenu(menu, family);
    }

    /** The Menu shape, with {@code children} taken from {@code all} and ordered as siblings. */
    private static Menu toMenu(MenuEntity menu, List<MenuEntity> all) {
        List<Menu> children = all.stream()
                .filter(child -> menu.getId().equals(child.getParentId()))
                .sorted(SIBLING_ORDER)
                .map(child -> toMenu(child, all))
                .toList();
        return new Menu(menu.getId(), menu.getKey(), menu.getLabel(), menu.getPath(), menu.getIcon(), menu.getType(),
                menu.getParentId(), menu.getSortOrder(), menu.isActive(), children);
    }
}
