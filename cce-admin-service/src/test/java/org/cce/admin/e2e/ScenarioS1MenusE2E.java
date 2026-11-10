package org.cce.admin.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cce.admin.e2e.E2eClients.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S1 and B5 (CUJ 1): two console roles with overlapping menus, synced, and a user holding both.
 * The user's {@code GET /me/menus} is the merged tree.
 */
class ScenarioS1MenusE2E {

    private static final String DASHBOARD = "10000000-0000-4000-8000-000000000001";
    private static final String COMPLIANCE = "10000000-0000-4000-8000-000000000003";
    private static final String PROTOCOL_DETAIL = "10000000-0000-4000-8000-000000000011";
    private static final String PATIENT_DETAIL = "10000000-0000-4000-8000-000000000012";

    private final E2eEnvironment env = E2eEnvironment.get();
    private final E2eClients clients = E2eClients.get();

    @AfterEach
    void resetStub() {
        StubMode.reset();
    }

    @Test
    void userWithTwoOverlappingRolesGetsTheMergedMenuTree() {
        String admin = TokenClient.adminToken();

        // A menu of this run, a NAV ordered between Compliance (30) and Deviations (40).
        Map<String, Object> menu = new LinkedHashMap<>();
        menu.put("key", env.menuKey("s1"));
        menu.put("label", "E2E S1 " + env.suffix());
        menu.put("path", "/e2e-s1-" + env.suffix());
        menu.put("icon", "SparklesIcon");
        menu.put("type", "NAV");
        menu.put("sortOrder", 35);
        Reply createdMenu = clients.post("/admin/v1/menus", admin, menu);
        assertThat(createdMenu.status()).as("%s", createdMenu).isEqualTo(201);
        String ownMenu = createdMenu.data().path("id").asText();

        // Role A: Dashboard, Compliance with its DETAIL, and a DETAIL whose parent nobody grants.
        JsonNode roleA = clients.createSyncedRole(admin, env.roleName("S1A"));
        Reply menusA = clients.put("/admin/v1/roles/" + roleA.path("id").asText() + "/menus", admin,
                Map.of("menuIds", List.of(DASHBOARD, COMPLIANCE, PROTOCOL_DETAIL, PATIENT_DETAIL)));
        assertThat(menusA.status()).as("%s", menusA).isEqualTo(200);

        // Role B overlaps role A on Compliance, and adds the run's own menu.
        JsonNode roleB = clients.createSyncedRole(admin, env.roleName("S1B"));
        Reply menusB = clients.put("/admin/v1/roles/" + roleB.path("id").asText() + "/menus", admin,
                Map.of("menuIds", List.of(COMPLIANCE, ownMenu)));
        assertThat(menusB.status()).as("%s", menusB).isEqualTo(200);

        JsonNode user = clients.createUser(admin, "s1",
                List.of(roleA.path("id").asText(), roleB.path("id").asText())).data();
        String token = clients.signIn(user);

        Reply me = clients.get("/admin/v1/me", token);
        assertThat(me.status()).as("%s", me).isEqualTo(200);
        assertThat(texts(me.data().path("roles")))
                .containsExactly(env.roleName("S1A"), env.roleName("S1B"));

        Reply tree = clients.get("/admin/v1/me/menus", token);
        assertThat(tree.status()).as("%s", tree).isEqualTo(200);
        JsonNode items = tree.data();
        // The union, ordered by sortOrder; Compliance once; Patient detail left out, because its
        // parent Patients is not granted (DEV-10).
        assertThat(keys(items)).containsExactly("dashboard", "compliance", env.menuKey("s1"));
        assertThat(keys(items.get(0).path("children"))).isEmpty();
        assertThat(keys(items.get(1).path("children"))).containsExactly("compliance.protocol-detail");
        assertThat(items.get(1).path("children").get(0).path("type").asText()).isEqualTo("DETAIL");
        assertThat(keys(items.get(2).path("children"))).isEmpty();
        assertThat(items.get(2).path("id").asText()).isEqualTo(ownMenu);
        assertThat(items.get(2).path("icon").asText()).isEqualTo("SparklesIcon");
    }

    private static List<String> keys(JsonNode items) {
        List<String> keys = new ArrayList<>();
        items.forEach(item -> keys.add(item.path("key").asText()));
        return keys;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
