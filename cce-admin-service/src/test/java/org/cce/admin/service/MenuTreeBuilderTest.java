package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.MeDtos.MenuTreeItem;
import org.cce.admin.entity.MenuEntity;
import org.cce.admin.entity.MenuType;
import org.junit.jupiter.api.Test;

/** The {@code GET /me/menus} tree rules of section 4.5 (B-21), on plain entities. */
class MenuTreeBuilderTest {

    @Test
    void anEmptyInputGivesAnEmptyTree() {
        assertThat(MenuTreeBuilder.build(List.of())).isEmpty();
    }

    @Test
    void navMenusAreTopLevelAndDetailsSitUnderTheirParent() {
        MenuEntity compliance = nav("compliance", "Compliance", 30);
        MenuEntity protocol = detail("compliance.protocol-detail", "Protocol analytics", 10, compliance);
        MenuEntity dashboard = nav("dashboard", "Dashboard", 10);

        List<MenuTreeItem> tree = MenuTreeBuilder.build(List.of(protocol, compliance, dashboard));

        assertThat(tree).extracting(MenuTreeItem::key).containsExactly("dashboard", "compliance");
        assertThat(tree.get(0).children()).isEmpty();
        assertThat(tree.get(1).children()).extracting(MenuTreeItem::key)
                .containsExactly("compliance.protocol-detail");
        MenuTreeItem child = tree.get(1).children().get(0);
        assertThat(child.children()).isEmpty();
        assertThat(child.type()).isEqualTo(MenuType.DETAIL);
        assertThat(child.id()).isEqualTo(protocol.getId());
        assertThat(child.label()).isEqualTo("Protocol analytics");
        assertThat(child.path()).isEqualTo("/compliance.protocol-detail");
        assertThat(child.sortOrder()).isEqualTo(10);
    }

    @Test
    void theItemCarriesEveryFieldOfTheMenu() {
        MenuEntity dashboard = nav("dashboard", "Dashboard", 10);
        dashboard.setIcon("ChartBarIcon");

        MenuTreeItem item = MenuTreeBuilder.build(List.of(dashboard)).get(0);

        assertThat(item.id()).isEqualTo(dashboard.getId());
        assertThat(item.key()).isEqualTo("dashboard");
        assertThat(item.label()).isEqualTo("Dashboard");
        assertThat(item.path()).isEqualTo("/dashboard");
        assertThat(item.icon()).isEqualTo("ChartBarIcon");
        assertThat(item.type()).isEqualTo(MenuType.NAV);
        assertThat(item.sortOrder()).isEqualTo(10);
        assertThat(item.children()).isEmpty();
    }

    @Test
    void aDetailWhoseParentIsNotInTheInputIsLeftOut() {
        MenuEntity patients = nav("patients", "Patients", 50);
        MenuEntity patientDetail = detail("patients.patient-detail", "Patient detail", 10, patients);
        MenuEntity events = nav("events", "Events", 60);

        List<MenuTreeItem> tree = MenuTreeBuilder.build(List.of(patientDetail, events));

        assertThat(tree).extracting(MenuTreeItem::key).containsExactly("events");
        assertThat(tree.get(0).children()).isEmpty();
    }

    @Test
    void siblingsAreOrderedBySortOrderThenLabel() {
        MenuEntity b = nav("b", "Bravo", 20);
        MenuEntity a = nav("a", "Alpha", 20);
        MenuEntity z = nav("z", "Zulu", 5);
        MenuEntity d2 = detail("a.two", "Two", 30, a);
        MenuEntity d1 = detail("a.one", "One", 30, a);
        MenuEntity d0 = detail("a.zero", "Zero", 1, a);

        List<MenuTreeItem> tree = MenuTreeBuilder.build(List.of(b, d2, a, d1, z, d0));

        assertThat(tree).extracting(MenuTreeItem::label).containsExactly("Zulu", "Alpha", "Bravo");
        assertThat(tree.get(1).children()).extracting(MenuTreeItem::label).containsExactly("Zero", "One", "Two");
    }

    @Test
    void aMenuGrantedByTwoRolesAppearsOnce() {
        MenuEntity dashboard = nav("dashboard", "Dashboard", 10);
        MenuEntity compliance = nav("compliance", "Compliance", 30);
        MenuEntity protocol = detail("compliance.protocol-detail", "Protocol analytics", 10, compliance);

        List<MenuTreeItem> tree = MenuTreeBuilder.build(
                List.of(dashboard, compliance, protocol, dashboard, compliance, protocol));

        assertThat(tree).extracting(MenuTreeItem::key).containsExactly("dashboard", "compliance");
        assertThat(tree.get(1).children()).hasSize(1);
    }

    private static MenuEntity nav(String key, String label, int sortOrder) {
        return menu(key, label, sortOrder, MenuType.NAV, null);
    }

    private static MenuEntity detail(String key, String label, int sortOrder, MenuEntity parent) {
        return menu(key, label, sortOrder, MenuType.DETAIL, parent.getId());
    }

    private static MenuEntity menu(String key, String label, int sortOrder, MenuType type, UUID parentId) {
        MenuEntity menu = new MenuEntity();
        menu.setId(UUID.randomUUID());
        menu.setKey(key);
        menu.setLabel(label);
        menu.setPath("/" + key);
        menu.setType(type);
        menu.setParentId(parentId);
        menu.setSortOrder(sortOrder);
        menu.setActive(true);
        return menu;
    }
}
