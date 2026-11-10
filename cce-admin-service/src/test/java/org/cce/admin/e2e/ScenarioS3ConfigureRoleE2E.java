package org.cce.admin.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.cce.admin.e2e.E2eClients.AuditRow;
import org.cce.admin.e2e.E2eClients.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * S3 (CUJ 3): an administrator creates and configures a role. Create gives {@code SYNCED} and a
 * realm role through the stand-in; a {@code DETAIL} granted without its parent gives the warning;
 * a permission save gives 200 and two audit rows.
 */
class ScenarioS3ConfigureRoleE2E {

    private static final String DASHBOARD = "10000000-0000-4000-8000-000000000001";
    private static final String PATIENTS = "10000000-0000-4000-8000-000000000005";
    private static final String PATIENT_DETAIL = "10000000-0000-4000-8000-000000000012";

    private final E2eEnvironment env = E2eEnvironment.get();
    private final E2eClients clients = E2eClients.get();

    @AfterEach
    void resetStub() {
        StubMode.reset();
    }

    @Test
    void createsARoleGrantsMenusAndSavesPermissions() {
        String admin = TokenClient.adminToken();
        String name = env.roleName("S3");

        // Create: 201, SYNCED, and the realm role now exists, tagged by the stand-in.
        Reply created = clients.post("/admin/v1/roles", admin,
                Map.of("name", name, "displayName", "E2E role " + name, "description", "Scenario S3"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        assertThat(created.body().has("warnings")).as("%s", created).isFalse();
        JsonNode role = created.data();
        String roleId = role.path("id").asText();
        assertThat(created.headers().getLocation()).hasToString("/admin/v1/roles/" + roleId);
        assertThat(role.path("syncStatus").asText()).isEqualTo("SYNCED");
        assertThat(role.path("lastSyncAt").isNull()).isFalse();
        RoleRepresentation realmRole = clients.realmRole(name).orElseThrow(
                () -> new AssertionError("realm role " + name + " does not exist after create"));
        assertThat(realmRole.getAttributes()).containsEntry("cce-managed", List.of("true"));

        // Menus: a DETAIL without its parent NAV gives DETAIL_WITHOUT_PARENT (B-41).
        Reply menus = clients.put("/admin/v1/roles/" + roleId + "/menus", admin,
                Map.of("menuIds", List.of(DASHBOARD, PATIENT_DETAIL)));
        assertThat(menus.status()).as("%s", menus).isEqualTo(200);
        assertThat(texts(menus.data().path("menuIds"))).containsExactlyInAnyOrder(DASHBOARD, PATIENT_DETAIL);
        JsonNode warnings = menus.body().path("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).path("code").asText()).isEqualTo("DETAIL_WITHOUT_PARENT");
        assertThat(warnings.get(0).path("menuId").asText()).isEqualTo(PATIENT_DETAIL);
        assertThat(warnings.get(0).path("parentMenuId").asText()).isEqualTo(PATIENTS);

        // Permissions: 200 after the sync succeeded, SYNCED, implicit rows first.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("httpMethod", "get");
        row.put("uriPattern", "/v1/insights/**");
        row.put("description", "Read insights");
        row.put("resourceCategory", "Insights");
        Reply saved = clients.put("/admin/v1/roles/" + roleId + "/permissions", admin,
                Map.of("permissions", List.of(row)));
        assertThat(saved.status()).as("%s", saved).isEqualTo(200);
        assertThat(saved.data().path("syncStatus").asText()).isEqualTo("SYNCED");
        JsonNode permissions = saved.data().path("permissions");
        assertThat(permissions).hasSize(3);
        assertThat(permissions.get(0).path("implicit").asBoolean()).isTrue();
        assertThat(permissions.get(1).path("implicit").asBoolean()).isTrue();
        assertThat(permissions.get(2).path("implicit").asBoolean()).isFalse();
        assertThat(permissions.get(2).path("httpMethod").asText()).isEqualTo("GET");
        assertThat(permissions.get(2).path("uriPattern").asText()).isEqualTo("/v1/insights/**");

        // The audit rows: create and its sync, the menu save, then the two rows of the save.
        List<AuditRow> rows = clients.auditRows("ROLE", UUID.fromString(roleId));
        assertThat(rows).extracting(AuditRow::action, AuditRow::outcome).containsExactly(
                tuple("ROLE_CREATED", "SUCCESS"),
                tuple("ROLE_SYNC_REQUESTED", "SUCCESS"),
                tuple("ROLE_MENUS_UPDATED", "SUCCESS"),
                tuple("ROLE_PERMISSIONS_UPDATED", "SUCCESS"),
                tuple("ROLE_SYNC_REQUESTED", "SUCCESS"));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
