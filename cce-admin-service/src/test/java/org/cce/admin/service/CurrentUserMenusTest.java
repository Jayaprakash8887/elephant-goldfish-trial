package org.cce.admin.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.cce.admin.entity.ApiPermissionEntity;
import org.cce.admin.entity.MenuEntity;
import org.cce.admin.entity.MenuType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.RoleMenuEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.ApiPermissionRepository;
import org.cce.admin.repository.MenuRepository;
import org.cce.admin.repository.RoleMenuRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET /admin/v1/me/menus} through the real pipeline (B-21, section 4.5): the union across the
 * caller's roles, inactive menus left out, a {@code DETAIL} only under a granted parent, ordering,
 * and the B5 case of two roles with overlapping menus. Each role holds the implicit
 * {@code GET /admin/v1/me/menus} row (AD-16), as a role created through the API does.
 */
class CurrentUserMenusTest extends DatabaseTestBase {

    private static final String ME_MENUS = "/admin/v1/me/menus";

    private static final UUID DASHBOARD = seedMenu(1);
    private static final UUID FACILITIES = seedMenu(2);
    private static final UUID COMPLIANCE = seedMenu(3);
    private static final UUID PATIENTS = seedMenu(5);
    private static final UUID EVENTS = seedMenu(6);
    private static final UUID PROTOCOL_DETAIL = seedMenu(11);
    private static final UUID PATIENT_DETAIL = seedMenu(12);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private RoleMenuRepository roleMenuRepository;

    @Autowired
    private ApiPermissionRepository permissionRepository;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        UserEntity entity = new UserEntity();
        entity.setKeycloakId(UUID.randomUUID());
        entity.setUsername("menus-user");
        entity.setEmail("menus-user@cce.local");
        entity.setFirstName("Menus");
        entity.setLastName("User");
        entity.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(entity);
    }

    @Test
    void twoOverlappingRolesGiveTheMergedTree() throws Exception {
        // B5: ANALYST has Dashboard, Compliance and its protocol detail; AUDITOR has Compliance,
        // Patients and the patient detail. The overlap (Compliance) appears once.
        role("ANALYST", DASHBOARD, COMPLIANCE, PROTOCOL_DETAIL);
        role("AUDITOR", COMPLIANCE, PATIENTS, PATIENT_DETAIL);

        call("ANALYST", "AUDITOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(DASHBOARD.toString()))
                .andExpect(jsonPath("$.data[0].key").value("dashboard"))
                .andExpect(jsonPath("$.data[0].label").value("Dashboard"))
                .andExpect(jsonPath("$.data[0].path").value("/"))
                .andExpect(jsonPath("$.data[0].icon").value("ChartBarIcon"))
                .andExpect(jsonPath("$.data[0].type").value("NAV"))
                .andExpect(jsonPath("$.data[0].sortOrder").value(10))
                .andExpect(jsonPath("$.data[0].children.length()").value(0))
                .andExpect(jsonPath("$.data[1].key").value("compliance"))
                .andExpect(jsonPath("$.data[1].children.length()").value(1))
                .andExpect(jsonPath("$.data[1].children[0].key").value("compliance.protocol-detail"))
                .andExpect(jsonPath("$.data[1].children[0].type").value("DETAIL"))
                .andExpect(jsonPath("$.data[1].children[0].icon").value((Object) null))
                .andExpect(jsonPath("$.data[1].children[0].children.length()").value(0))
                .andExpect(jsonPath("$.data[2].key").value("patients"))
                .andExpect(jsonPath("$.data[2].children.length()").value(1))
                .andExpect(jsonPath("$.data[2].children[0].key").value("patients.patient-detail"));

        // Each role alone gives only its own menus.
        call("ANALYST")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].key").value("dashboard"))
                .andExpect(jsonPath("$.data[1].key").value("compliance"));
        call("AUDITOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].key").value("compliance"))
                .andExpect(jsonPath("$.data[0].children.length()").value(0))
                .andExpect(jsonPath("$.data[1].key").value("patients"));
    }

    @Test
    void inactiveMenusAreLeftOut() throws Exception {
        UUID inactiveNav = menu("inactive-nav", "Inactive", 15, MenuType.NAV, null, false);
        UUID inactiveDetail = menu("compliance.inactive-detail", "Inactive detail", 20, MenuType.DETAIL, COMPLIANCE,
                false);
        role("VIEWER", DASHBOARD, inactiveNav, COMPLIANCE, inactiveDetail);

        call("VIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].key").value("dashboard"))
                .andExpect(jsonPath("$.data[1].key").value("compliance"))
                .andExpect(jsonPath("$.data[1].children.length()").value(0));
    }

    @Test
    void aDetailWhoseParentIsInactiveIsLeftOut() throws Exception {
        UUID inactiveParent = menu("inactive-parent", "Inactive parent", 15, MenuType.NAV, null, false);
        UUID detail = menu("inactive-parent.detail", "Detail", 10, MenuType.DETAIL, inactiveParent, true);
        role("VIEWER", DASHBOARD, inactiveParent, detail);

        call("VIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].key").value("dashboard"))
                .andExpect(jsonPath("$.data[0].children.length()").value(0));
    }

    @Test
    void aDetailIsShownOnlyUnderAGrantedParent() throws Exception {
        // The patient detail is granted without Patients (DEV-10); Patients comes from a second role.
        role("DETAIL_ONLY", EVENTS, PATIENT_DETAIL);
        role("PATIENTS_ROLE", PATIENTS);

        call("DETAIL_ONLY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].key").value("events"))
                .andExpect(jsonPath("$.data[0].children.length()").value(0));

        call("DETAIL_ONLY", "PATIENTS_ROLE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].key").value("patients"))
                .andExpect(jsonPath("$.data[0].children.length()").value(1))
                .andExpect(jsonPath("$.data[0].children[0].key").value("patients.patient-detail"))
                .andExpect(jsonPath("$.data[1].key").value("events"));
    }

    @Test
    void siblingsAreOrderedBySortOrderThenLabel() throws Exception {
        UUID zulu = menu("zulu", "Zulu", 25, MenuType.NAV, null, true);
        UUID alpha = menu("alpha", "Alpha", 25, MenuType.NAV, null, true);
        UUID second = menu("compliance.b-detail", "B detail", 5, MenuType.DETAIL, COMPLIANCE, true);
        UUID first = menu("compliance.a-detail", "A detail", 5, MenuType.DETAIL, COMPLIANCE, true);
        role("ORDER_ROLE", FACILITIES, zulu, COMPLIANCE, PROTOCOL_DETAIL, second, first, alpha, DASHBOARD);

        call("ORDER_ROLE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].key").value("dashboard"))
                .andExpect(jsonPath("$.data[1].key").value("facilities"))
                .andExpect(jsonPath("$.data[2].key").value("alpha"))
                .andExpect(jsonPath("$.data[3].key").value("zulu"))
                .andExpect(jsonPath("$.data[4].key").value("compliance"))
                .andExpect(jsonPath("$.data[4].children.length()").value(3))
                .andExpect(jsonPath("$.data[4].children[0].key").value("compliance.a-detail"))
                .andExpect(jsonPath("$.data[4].children[1].key").value("compliance.b-detail"))
                .andExpect(jsonPath("$.data[4].children[2].key").value("compliance.protocol-detail"));
    }

    @Test
    void aCallerWithNoMatchingGrantsGetsAnEmptyList() throws Exception {
        // VIEWER's menus do not leak to a caller who does not hold it. Token roles that are not
        // roles here (Keycloak's own, or a name no row has) grant nothing.
        role("VIEWER", DASHBOARD);
        role("NO_MENUS");
        call("NO_MENUS", "offline_access", "default-roles-cce", "NOT_A_ROLE_HERE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void superAdminWithNoGrantedMenusGetsAnEmptyList() throws Exception {
        // SUPER_ADMIN is allowed by its /admin/v1/** row and is granted no menus by the seed.
        call("SUPER_ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // -------------------------------------------------------------------------------- helpers

    private ResultActions call(String... roles) throws Exception {
        String bearer = TestJwtFactory.token().subject(user.getKeycloakId()).roles(roles).bearer();
        return mockMvc.perform(get(ME_MENUS).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** A console role with the implicit {@code /me/menus} row (AD-16) and the given menus. */
    private UUID role(String name, UUID... menuIds) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName(name);
        role.setSystem(false);
        role.setAssignable(true);
        UUID id = roleRepository.save(role).getId();
        ApiPermissionEntity row = new ApiPermissionEntity();
        row.setPermissionName(name);
        row.setHttpMethod("GET");
        row.setUriPattern(ME_MENUS);
        row.setDescription("Read own menu tree");
        row.setResourceCategory("Self");
        permissionRepository.save(row);
        for (UUID menuId : menuIds) {
            RoleMenuEntity grant = new RoleMenuEntity();
            grant.setRoleId(id);
            grant.setMenuId(menuId);
            roleMenuRepository.save(grant);
        }
        return id;
    }

    private UUID menu(String key, String label, int sortOrder, MenuType type, UUID parentId, boolean active) {
        MenuEntity menu = new MenuEntity();
        menu.setKey(key);
        menu.setLabel(label);
        menu.setPath("/" + key.replace('.', '/'));
        menu.setType(type);
        menu.setParentId(parentId);
        menu.setSortOrder(sortOrder);
        menu.setActive(active);
        return menuRepository.save(menu).getId();
    }

    private static UUID seedMenu(int row) {
        return UUID.fromString(String.format("10000000-0000-4000-8000-%012d", row));
    }
}
