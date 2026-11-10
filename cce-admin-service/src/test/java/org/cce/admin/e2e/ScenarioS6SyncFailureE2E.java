package org.cce.admin.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.cce.admin.e2e.E2eClients.AuditRow;
import org.cce.admin.e2e.E2eClients.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S6 (CUJ 6): the gateway sync fails after a permission save. With the stand-in failing, a new role
 * is created with {@code ROLE_SYNC_FAILED}, its permission save gives 502
 * {@code ROLE_PERMISSIONS_SAVE} with the rows kept, and assigning it gives 409
 * {@code ROLE_NOT_SYNCED}. After the stand-in recovers, a re-sync gives 200 and the assignment works.
 */
class ScenarioS6SyncFailureE2E {

    private final E2eEnvironment env = E2eEnvironment.get();
    private final E2eClients clients = E2eClients.get();

    @AfterEach
    void resetStub() {
        StubMode.reset();
    }

    @Test
    void aFailedSyncKeepsTheRowsAndBlocksAssignmentUntilAReSync() {
        String admin = TokenClient.adminToken();

        // Before the stand-in fails: a user holding one synced role.
        String syncedName = env.roleName("S6A");
        String syncedId = clients.createSyncedRole(admin, syncedName).path("id").asText();
        JsonNode user = clients.createUser(admin, "s6", List.of(syncedId)).data();
        String userId = user.path("id").asText();
        UUID keycloakId = UUID.fromString(user.path("keycloakId").asText());

        StubMode.fail();
        assertThat(StubMode.current()).isEqualTo("fail");

        // Create: 201 with ROLE_SYNC_FAILED, syncStatus FAILED, no realm role.
        String name = env.roleName("S6B");
        Reply created = clients.post("/admin/v1/roles", admin, Map.of("name", name, "displayName", "E2E role " + name));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        JsonNode warnings = created.body().path("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).path("code").asText()).isEqualTo("ROLE_SYNC_FAILED");
        assertThat(warnings.get(0).has("menuId")).isFalse();
        assertThat(created.data().path("syncStatus").asText()).isEqualTo("FAILED");
        String roleId = created.data().path("id").asText();
        assertThat(clients.realmRole(name)).isEqualTo(Optional.empty());

        // Permission save: 502 ROLE_PERMISSIONS_SAVE, the rows are saved, a FAILED sync row.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("httpMethod", "GET");
        row.put("uriPattern", "/v1/insights/**");
        row.put("description", "Read insights");
        row.put("resourceCategory", "Insights");
        Reply saved = clients.put("/admin/v1/roles/" + roleId + "/permissions", admin, Map.of("permissions", List.of(row)));
        assertThat(saved.status()).as("%s", saved).isEqualTo(502);
        assertThat(saved.errorCode()).isEqualTo("KEYCLOAK_SYNC_FAILED");
        JsonNode partialState = saved.body().path("error").path("partialState");
        assertThat(partialState.path("operation").asText()).isEqualTo("ROLE_PERMISSIONS_SAVE");
        assertThat(partialState.path("entityId").asText()).isEqualTo(roleId);
        assertThat(partialState.path("failedStep").asText()).isEqualTo("GATEWAY_SYNC");
        assertThat(texts(partialState.path("completedSteps"))).containsExactly("PERMISSIONS_SAVED");
        assertThat(partialState.path("keycloakUserId").isNull()).isTrue();
        String auditLogId = partialState.path("auditLogId").asText();

        Reply stored = clients.get("/admin/v1/roles/" + roleId + "/permissions", admin);
        assertThat(stored.status()).as("%s", stored).isEqualTo(200);
        assertThat(stored.data().path("syncStatus").asText()).isEqualTo("FAILED");
        assertThat(stored.data().path("permissions").findValuesAsText("uriPattern")).contains("/v1/insights/**");
        assertThat(stored.data().path("permissions")).hasSize(3);

        List<AuditRow> rows = clients.auditRows("ROLE", UUID.fromString(roleId));
        assertThat(rows).extracting(AuditRow::action, AuditRow::outcome).containsExactly(
                tuple("ROLE_CREATED", "SUCCESS"),
                tuple("ROLE_SYNC_REQUESTED", "FAILED"),
                tuple("ROLE_PERMISSIONS_UPDATED", "SUCCESS"),
                tuple("ROLE_SYNC_REQUESTED", "FAILED"));
        assertThat(rows.get(3).id()).hasToString(auditLogId);
        assertThat(rows.get(3).afterState().path("httpStatus").asInt()).isEqualTo(503);

        // Assignment: 409 ROLE_NOT_SYNCED naming the role, and nothing changes.
        Reply refused = clients.put("/admin/v1/users/" + userId + "/roles", admin,
                Map.of("roleIds", List.of(syncedId, roleId)));
        assertThat(refused.status()).as("%s", refused).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("ROLE_NOT_SYNCED");
        JsonNode details = refused.body().path("error").path("details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).path("field").asText()).isEqualTo("roleIds[1]");
        assertThat(clients.realmRoleNames(keycloakId)).doesNotContain(name);

        // Recovery: the stand-in is ok again, a re-sync gives 200, and the assignment works.
        StubMode.ok();
        Reply resynced = clients.post("/admin/v1/roles/" + roleId + "/sync", admin, null);
        assertThat(resynced.status()).as("%s", resynced).isEqualTo(200);
        assertThat(resynced.data().path("syncStatus").asText()).isEqualTo("SYNCED");
        assertThat(resynced.data().path("roleId").asText()).isEqualTo(roleId);
        assertThat(clients.realmRole(name)).isPresent();

        Reply assigned = clients.put("/admin/v1/users/" + userId + "/roles", admin,
                Map.of("roleIds", List.of(syncedId, roleId)));
        assertThat(assigned.status()).as("%s", assigned).isEqualTo(200);
        assertThat(assigned.data().path("roles").findValuesAsText("name")).containsExactly(syncedName, name);
        assertThat(clients.realmRoleNames(keycloakId)).contains(syncedName, name);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
