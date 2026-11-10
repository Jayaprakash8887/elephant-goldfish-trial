package org.cce.admin.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.cce.admin.audit.AuditAction;
import org.cce.admin.audit.AuditEntry;
import org.cce.admin.audit.AuditWriter;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.CommonDtos.Warning;
import org.cce.admin.controller.dto.RoleDtos.ReplaceMenusRequest;
import org.cce.admin.controller.dto.RoleDtos.RoleMenus;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.MenuEntity;
import org.cce.admin.entity.MenuType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.RoleMenuEntity;
import org.cce.admin.repository.MenuRepository;
import org.cce.admin.repository.RoleMenuRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A role's menus: get and replace (5.11), with the {@code DETAIL_WITHOUT_PARENT} warnings (B-41).
 * No class- or method-level {@code @Transactional}.
 */
@Service
public class RoleMenuService {

    /** The replaced set and its warnings. Warnings are returned on a no-op too (section 4.5). */
    public record RoleMenusResult(RoleMenus menus, List<Warning> warnings) {
    }

    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;
    private final RoleMenuRepository roleMenuRepository;
    private final AuditWriter auditWriter;
    private final TransactionTemplate transaction;

    public RoleMenuService(RoleRepository roleRepository, MenuRepository menuRepository,
            RoleMenuRepository roleMenuRepository, AuditWriter auditWriter,
            PlatformTransactionManager transactionManager) {
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.roleMenuRepository = roleMenuRepository;
        this.auditWriter = auditWriter;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** {@code GET /roles/:id/menus}. Includes inactive menus that were granted (AS-REQ-15). */
    public RoleMenus get(UUID roleId) {
        load(roleId);
        return new RoleMenus(roleId, grantedIds(roleId));
    }

    /** {@code PUT /roles/:id/menus} (5.11). */
    public RoleMenusResult replace(UUID roleId, ReplaceMenusRequest request) {
        // Step 1.
        RoleEntity role = load(roleId);
        RoleService.requireNotSystem(role);

        // Step 2: distinct ids of existing menus.
        List<UUID> requested = request.menuIds();
        Map<UUID, MenuEntity> menus = menuRepository.findAllById(new HashSet<>(requested)).stream()
                .collect(Collectors.toMap(MenuEntity::getId, Function.identity()));
        List<FieldErrorDto> details = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            UUID id = requested.get(i);
            if (!seen.add(id)) {
                details.add(new FieldErrorDto("menuIds[" + i + "]", "The same menu id is listed twice."));
            } else if (!menus.containsKey(id)) {
                details.add(new FieldErrorDto("menuIds[" + i + "]", "No menu has this id."));
            }
        }
        if (!details.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The request is not valid.", details);
        }

        // Step 3: one warning per DETAIL menu granted without its parent NAV, in request order.
        List<Warning> warnings = new ArrayList<>();
        for (UUID id : requested) {
            MenuEntity menu = menus.get(id);
            if (menu.getType() == MenuType.DETAIL && !seen.contains(menu.getParentId())) {
                warnings.add(Warning.detailWithoutParent("Menu " + menu.getKey() + " is a DETAIL menu granted"
                        + " without its parent. It is not shown until its parent is granted too.",
                        menu.getId(), menu.getParentId()));
            }
        }

        // Step 4: no-op when the set is unchanged; the warnings are still returned.
        List<UUID> after = sortedIds(seen);
        List<UUID> before = grantedIds(roleId);
        if (before.equals(after)) {
            return new RoleMenusResult(new RoleMenus(roleId, before), warnings);
        }

        // Step 5: replace the rows and write ROLE_MENUS_UPDATED, in one transaction.
        transaction.executeWithoutResult(status -> {
            List<UUID> current = grantedIds(roleId);
            roleMenuRepository.deleteByRoleId(roleId);
            for (UUID menuId : after) {
                RoleMenuEntity row = new RoleMenuEntity();
                row.setRoleId(roleId);
                row.setMenuId(menuId);
                roleMenuRepository.save(row);
            }
            auditWriter.record(AuditEntry.success(AuditAction.ROLE_MENUS_UPDATED, AuditEntityType.ROLE, roleId,
                    Map.of("menuIds", current), Map.of("menuIds", after)));
        });
        return new RoleMenusResult(new RoleMenus(roleId, after), warnings);
    }

    private RoleEntity load(UUID roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROLE_NOT_FOUND, "No role has this id."));
    }

    private List<UUID> grantedIds(UUID roleId) {
        return sortedIds(roleMenuRepository.findByRoleId(roleId).stream().map(RoleMenuEntity::getMenuId).toList());
    }

    /**
     * Menu ids in a stable order.
     * DESIGN-GAP: section 4.4 does not order {@code RoleMenus.menuIds}. They are ordered by their
     * string form, so a response and an audit row list the same set the same way every time.
     */
    static List<UUID> sortedIds(Collection<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }
}
