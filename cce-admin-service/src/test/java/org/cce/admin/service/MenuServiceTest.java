package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.MenuDtos.CreateMenuRequest;
import org.cce.admin.controller.dto.MenuDtos.Menu;
import org.cce.admin.controller.dto.MenuDtos.UpdateMenuRequest;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The menu catalog (B-44): tree, create, update and delete (5.12), with their audit rows. */
class MenuServiceTest extends DatabaseTestBase {

    private static final UUID COMPLIANCE = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID PROTOCOL_DETAIL = UUID.fromString("10000000-0000-4000-8000-000000000011");

    @Autowired
    private MenuService menuService;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleMenuRepository roleMenuRepository;

    @Autowired
    private AuditLogRepository auditLog;

    // ------------------------------------------------------------------------------------ tree

    @Test
    void theTreeHoldsTheTenSeedNavMenusInOrderWithTheirDetailChildren() {
        List<Menu> tree = menuService.tree();

        assertThat(tree).extracting(Menu::key).containsExactly("dashboard", "facilities", "compliance", "deviations",
                "patients", "events", "ingestion", "practitioners", "intelligence", "exports");
        Menu compliance = tree.get(2);
        assertThat(compliance.children()).extracting(Menu::key).containsExactly("compliance.protocol-detail");
        assertThat(compliance.children().get(0).parentId()).isEqualTo(COMPLIANCE);
        assertThat(compliance.children().get(0).children()).isEmpty();
        assertThat(tree.get(4).children()).extracting(Menu::key).containsExactly("patients.patient-detail");
        assertThat(tree.get(0).children()).isEmpty();
        assertThat(tree.get(0).icon()).isEqualTo("ChartBarIcon");
        assertThat(tree.get(0).isActive()).isTrue();
    }

    @Test
    void theTreeIncludesInactiveMenusAndOrdersSiblingsBySortOrderThenLabel() {
        menuService.create(nav("zz-b", "Bravo", 15, false));
        menuService.create(nav("zz-a", "Alpha", 15, true));
        menuService.create(detail("compliance.zz-early", "Early", COMPLIANCE, 5));

        List<Menu> tree = menuService.tree();

        assertThat(tree).extracting(Menu::key).startsWith("dashboard", "zz-a", "zz-b", "facilities");
        assertThat(tree.get(2).isActive()).isFalse();
        Menu compliance = tree.stream().filter(m -> m.key().equals("compliance")).findFirst().orElseThrow();
        assertThat(compliance.children()).extracting(Menu::key)
                .containsExactly("compliance.zz-early", "compliance.protocol-detail");
    }

    // ---------------------------------------------------------------------------------- create

    @Test
    void createSavesAMenuAndWritesMenuCreated() {
        Menu created = menuService.create(new CreateMenuRequest("reports", "Reports", "/reports", "DocumentIcon",
                MenuType.NAV, null, 110, null));

        assertThat(created.id()).isNotNull();
        assertThat(created.isActive()).isTrue();
        assertThat(created.children()).isEmpty();
        assertThat(menuRepository.findById(created.id())).isPresent();

        List<AuditLogEntity> rows = menuRows(created.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo("MENU_CREATED");
        assertThat(rows.get(0).getBeforeState()).isNull();
        assertThat(rows.get(0).getAfterState().path("key").asText()).isEqualTo("reports");
        assertThat(rows.get(0).getAfterState().path("isActive").asBoolean()).isTrue();
        verifyNoInteractions(keycloakGateway, gatewaySyncClient);
    }

    @Test
    void createOfADetailUnderANav() {
        Menu created = menuService.create(detail("compliance.history", "History", COMPLIANCE, 20));
        assertThat(created.type()).isEqualTo(MenuType.DETAIL);
        assertThat(created.parentId()).isEqualTo(COMPLIANCE);
    }

    @Test
    void createRefusesADuplicateKey() {
        expectCode(() -> menuService.create(nav("dashboard", "Again", 1, true)), ErrorCode.DUPLICATE_MENU_KEY);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void createChecksTheParentRules() {
        expectParentError(() -> menuService.create(new CreateMenuRequest("nav-with-parent", "x", "/x", null,
                MenuType.NAV, COMPLIANCE, 1, true)));
        expectParentError(() -> menuService.create(new CreateMenuRequest("detail-alone", "x", "/x", null,
                MenuType.DETAIL, null, 1, true)));
        expectParentError(() -> menuService.create(detail("detail-under-detail", "x", PROTOCOL_DETAIL, 1)));
        expectParentError(() -> menuService.create(detail("detail-under-nothing", "x", UUID.randomUUID(), 1)));
        assertThat(auditLog.count()).isZero();
    }

    // ---------------------------------------------------------------------------------- update

    @Test
    void updateReplacesTheFieldsAndWritesMenuUpdated() {
        Menu menu = menuService.create(nav("editable", "Editable", 200, true));
        jdbcTemplate.execute("TRUNCATE audit_log");

        Menu updated = menuService.update(menu.id(),
                new UpdateMenuRequest("Edited", "/edited/:id", null, null, 210, false));

        assertThat(updated.label()).isEqualTo("Edited");
        assertThat(updated.path()).isEqualTo("/edited/:id");
        assertThat(updated.icon()).isNull();
        assertThat(updated.sortOrder()).isEqualTo(210);
        assertThat(updated.isActive()).isFalse();
        assertThat(updated.key()).isEqualTo("editable");

        List<AuditLogEntity> rows = menuRows(menu.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo("MENU_UPDATED");
        assertThat(rows.get(0).getBeforeState().path("label").asText()).isEqualTo("Editable");
        assertThat(rows.get(0).getBeforeState().path("icon").asText()).isEqualTo("SquaresPlusIcon");
        assertThat(rows.get(0).getAfterState().path("label").asText()).isEqualTo("Edited");
        assertThat(rows.get(0).getAfterState().path("isActive").asBoolean()).isFalse();
    }

    @Test
    void anUnchangedUpdateIsANoOp() {
        Menu menu = menuService.create(nav("same", "Same", 200, true));
        jdbcTemplate.execute("TRUNCATE audit_log");

        Menu same = menuService.update(menu.id(),
                new UpdateMenuRequest("Same", "/same", "SquaresPlusIcon", null, 200, true));

        assertThat(same).isEqualTo(menu);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void updateMovesADetailToAnotherNavAndChecksTheParentRules() {
        Menu detail = menuService.create(detail("compliance.movable", "Movable", COMPLIANCE, 30));
        Menu otherNav = menuService.create(nav("other-nav", "Other", 300, true));

        Menu moved = menuService.update(detail.id(),
                new UpdateMenuRequest("Movable", "/movable", null, otherNav.id(), 30, true));
        assertThat(moved.parentId()).isEqualTo(otherNav.id());

        expectParentError(() -> menuService.update(detail.id(),
                new UpdateMenuRequest("Movable", "/movable", null, null, 30, true)));
        expectParentError(() -> menuService.update(detail.id(),
                new UpdateMenuRequest("Movable", "/movable", null, detail.id(), 30, true)));
        expectParentError(() -> menuService.update(otherNav.id(),
                new UpdateMenuRequest("Other", "/other", null, COMPLIANCE, 300, true)));
    }

    @Test
    void updateOfAMissingMenuIs404() {
        expectCode(() -> menuService.update(UUID.randomUUID(), new UpdateMenuRequest("x", "/x", null, null, 1, true)),
                ErrorCode.MENU_NOT_FOUND);
    }

    // ---------------------------------------------------------------------------------- delete

    @Test
    void deleteRemovesTheMenuAndItsGrantsAndRecordsTheRoles() {
        Menu menu = menuService.create(nav("doomed", "Doomed", 400, true));
        RoleEntity role = new RoleEntity();
        role.setName("MENU_HOLDER");
        role.setDisplayName("Holder");
        role.setAssignable(true);
        UUID roleId = roleRepository.save(role).getId();
        RoleMenuEntity grant = new RoleMenuEntity();
        grant.setRoleId(roleId);
        grant.setMenuId(menu.id());
        roleMenuRepository.save(grant);
        jdbcTemplate.execute("TRUNCATE audit_log");

        menuService.delete(menu.id());

        assertThat(menuRepository.findById(menu.id())).isEmpty();
        assertThat(roleMenuRepository.findByRoleId(roleId)).isEmpty();
        List<AuditLogEntity> rows = menuRows(menu.id());
        assertThat(rows).hasSize(1);
        assertThat(auditLog.count()).isEqualTo(1);
        assertThat(rows.get(0).getAction()).isEqualTo("MENU_DELETED");
        assertThat(rows.get(0).getAfterState()).isNull();
        assertThat(rows.get(0).getBeforeState().path("key").asText()).isEqualTo("doomed");
        assertThat(rows.get(0).getBeforeState().path("roleIds").get(0).asText()).isEqualTo(roleId.toString());
        verify(gatewaySyncClient, never()).sync();
    }

    @Test
    void deleteOfAMenuWithChildrenIsRefused() {
        Menu parent = menuService.create(nav("parent-nav", "Parent", 500, true));
        menuService.create(detail("parent-nav.child", "Child", parent.id(), 1));
        jdbcTemplate.execute("TRUNCATE audit_log");

        expectCode(() -> menuService.delete(parent.id()), ErrorCode.MENU_HAS_CHILDREN);
        assertThat(menuRepository.findById(parent.id())).isPresent();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void deleteOfAMissingMenuIs404() {
        expectCode(() -> menuService.delete(UUID.randomUUID()), ErrorCode.MENU_NOT_FOUND);
    }

    // -------------------------------------------------------------------------------- helpers

    private static CreateMenuRequest nav(String key, String label, int sortOrder, boolean active) {
        return new CreateMenuRequest(key, label, "/" + key, "SquaresPlusIcon", MenuType.NAV, null, sortOrder, active);
    }

    private static CreateMenuRequest detail(String key, String label, UUID parentId, int sortOrder) {
        return new CreateMenuRequest(key, label, "/" + key.replace('.', '/') + "/:id", null, MenuType.DETAIL,
                parentId, sortOrder, null);
    }

    private List<AuditLogEntity> menuRows(UUID menuId) {
        return auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.MENU, menuId);
    }

    private static void expectParentError(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(e.details()).extracting(FieldErrorDto::field).containsExactly("parentId");
        });
    }

    private static void expectCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
