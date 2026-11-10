package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.CommonDtos.Warning;
import org.cce.admin.controller.dto.RoleDtos.ReplaceMenusRequest;
import org.cce.admin.controller.dto.RoleDtos.RoleMenus;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.MenuEntity;
import org.cce.admin.entity.MenuType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.RoleMenuEntity;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.repository.MenuRepository;
import org.cce.admin.repository.RoleMenuRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A role's menus: get and replace (5.11), the DETAIL_WITHOUT_PARENT warnings (B-41) and the audit rows. */
class RoleMenuServiceTest extends DatabaseTestBase {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID DASHBOARD = seedMenu(1);
    private static final UUID COMPLIANCE = seedMenu(3);
    private static final UUID PATIENTS = seedMenu(5);
    private static final UUID PROTOCOL_DETAIL = seedMenu(11);
    private static final UUID PATIENT_DETAIL = seedMenu(12);

    @Autowired
    private RoleMenuService roleMenuService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private RoleMenuRepository roleMenuRepository;

    @Autowired
    private AuditLogRepository auditLog;

    private UUID roleId;

    @BeforeEach
    void createRole() {
        RoleEntity role = new RoleEntity();
        role.setName("MENU_ROLE");
        role.setDisplayName("Menus");
        role.setSystem(false);
        role.setAssignable(true);
        roleId = roleRepository.save(role).getId();
    }

    @Test
    void getReturnsTheGrantedIdsIncludingInactiveMenus() {
        UUID inactive = inactiveMenu();
        grant(DASHBOARD);
        grant(inactive);

        RoleMenus menus = roleMenuService.get(roleId);

        assertThat(menus.roleId()).isEqualTo(roleId);
        assertThat(menus.menuIds()).containsExactlyInAnyOrder(DASHBOARD, inactive);
        expectCode(() -> roleMenuService.get(UUID.randomUUID()), ErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void replaceWritesTheSetAndOneRow() {
        grant(DASHBOARD);

        RoleMenuService.RoleMenusResult result = roleMenuService.replace(roleId,
                new ReplaceMenusRequest(List.of(COMPLIANCE, PROTOCOL_DETAIL)));

        assertThat(result.warnings()).isEmpty();
        assertThat(result.menus().menuIds()).containsExactlyInAnyOrder(COMPLIANCE, PROTOCOL_DETAIL);
        assertThat(roleMenuRepository.findByRoleId(roleId)).extracting(RoleMenuEntity::getMenuId)
                .containsExactlyInAnyOrder(COMPLIANCE, PROTOCOL_DETAIL);

        List<AuditLogEntity> rows = roleRows();
        assertThat(rows).hasSize(1);
        assertThat(auditLog.count()).isEqualTo(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("ROLE_MENUS_UPDATED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getBeforeState().path("menuIds")).hasSize(1);
        assertThat(row.getBeforeState().path("menuIds").get(0).asText()).isEqualTo(DASHBOARD.toString());
        assertThat(row.getAfterState().path("menuIds")).hasSize(2);
        verify(gatewaySyncClient, never()).sync();
    }

    @Test
    void aDetailWithoutItsParentGivesOneWarningEach() {
        RoleMenuService.RoleMenusResult result = roleMenuService.replace(roleId,
                new ReplaceMenusRequest(List.of(PATIENT_DETAIL, DASHBOARD, PROTOCOL_DETAIL, PATIENTS)));

        assertThat(result.warnings()).hasSize(1);
        Warning warning = result.warnings().get(0);
        assertThat(warning.code()).isEqualTo("DETAIL_WITHOUT_PARENT");
        assertThat(warning.menuId()).isEqualTo(PROTOCOL_DETAIL);
        assertThat(warning.parentMenuId()).isEqualTo(COMPLIANCE);
        assertThat(warning.message()).isNotBlank();
        assertThat(roleRows()).hasSize(1);
    }

    @Test
    void anUnchangedSetIsANoOpThatStillReturnsTheWarnings() {
        grant(PROTOCOL_DETAIL);

        RoleMenuService.RoleMenusResult result = roleMenuService.replace(roleId,
                new ReplaceMenusRequest(List.of(PROTOCOL_DETAIL)));

        assertThat(result.menus().menuIds()).containsExactly(PROTOCOL_DETAIL);
        assertThat(result.warnings()).extracting(Warning::code).containsExactly("DETAIL_WITHOUT_PARENT");
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anEmptySetRemovesEveryGrant() {
        grant(DASHBOARD);
        RoleMenuService.RoleMenusResult result = roleMenuService.replace(roleId, new ReplaceMenusRequest(List.of()));
        assertThat(result.menus().menuIds()).isEmpty();
        assertThat(roleMenuRepository.findByRoleId(roleId)).isEmpty();
        assertThat(roleRows()).hasSize(1);
    }

    @Test
    void anInactiveMenuMayBeGranted() {
        UUID inactive = inactiveMenu();
        assertThat(roleMenuService.replace(roleId, new ReplaceMenusRequest(List.of(inactive))).menus().menuIds())
                .containsExactly(inactive);
    }

    @Test
    void unknownAndRepeatedIdsAre400() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> roleMenuService.replace(roleId,
                new ReplaceMenusRequest(List.of(DASHBOARD, unknown, DASHBOARD))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.details()).extracting(FieldErrorDto::field).containsExactly("menuIds[1]", "menuIds[2]");
                });
        assertThat(roleMenuRepository.findByRoleId(roleId)).isEmpty();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aSystemRoleOrAMissingRoleIsRefused() {
        expectCode(() -> roleMenuService.replace(SUPER_ADMIN_ID, new ReplaceMenusRequest(List.of(DASHBOARD))),
                ErrorCode.SYSTEM_ROLE_IMMUTABLE);
        expectCode(() -> roleMenuService.replace(UUID.randomUUID(), new ReplaceMenusRequest(List.of(DASHBOARD))),
                ErrorCode.ROLE_NOT_FOUND);
        assertThat(roleMenuRepository.findByRoleId(SUPER_ADMIN_ID)).isEmpty();
        assertThat(auditLog.count()).isZero();
    }

    // -------------------------------------------------------------------------------- helpers

    private void grant(UUID menuId) {
        RoleMenuEntity row = new RoleMenuEntity();
        row.setRoleId(roleId);
        row.setMenuId(menuId);
        roleMenuRepository.save(row);
    }

    private UUID inactiveMenu() {
        MenuEntity menu = new MenuEntity();
        menu.setKey("inactive-" + UUID.randomUUID().toString().substring(0, 8));
        menu.setLabel("Inactive");
        menu.setPath("/inactive");
        menu.setType(MenuType.NAV);
        menu.setSortOrder(500);
        menu.setActive(false);
        return menuRepository.save(menu).getId();
    }

    private List<AuditLogEntity> roleRows() {
        return auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.ROLE, roleId);
    }

    private static UUID seedMenu(int row) {
        return UUID.fromString(String.format("10000000-0000-4000-8000-%012d", row));
    }

    private static void expectCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
