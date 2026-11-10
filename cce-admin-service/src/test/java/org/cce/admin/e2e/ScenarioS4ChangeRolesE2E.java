package org.cce.admin.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.cce.admin.e2e.E2eClients.AuditRow;
import org.cce.admin.e2e.E2eClients.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S4 (CUJ 4): an administrator replaces a user's roles with the complete new set. Only the added
 * and removed realm role mappings move in Keycloak (B-35), and each change writes one
 * {@code USER_ROLES_UPDATED} row.
 */
class ScenarioS4ChangeRolesE2E {

    private final E2eEnvironment env = E2eEnvironment.get();
    private final E2eClients clients = E2eClients.get();

    @AfterEach
    void resetStub() {
        StubMode.reset();
    }

    @Test
    void replacingTheSetMovesOnlyTheChangedMappings() {
        String admin = TokenClient.adminToken();
        String nameA = env.roleName("S4A");
        String nameB = env.roleName("S4B");
        String roleA = clients.createSyncedRole(admin, nameA).path("id").asText();
        String roleB = clients.createSyncedRole(admin, nameB).path("id").asText();

        JsonNode user = clients.createUser(admin, "s4", List.of(roleA)).data();
        String userId = user.path("id").asText();
        UUID keycloakId = UUID.fromString(user.path("keycloakId").asText());

        // The mappings the service did not make (Keycloak's default role) are the control: a
        // whole-set replace in Keycloak would remove them.
        Set<String> initial = clients.realmRoleNames(keycloakId);
        assertThat(initial).contains(nameA).doesNotContain(nameB);
        Set<String> untouched = new HashSet<>(initial);
        untouched.remove(nameA);
        assertThat(untouched).as("mappings Keycloak made itself").isNotEmpty();

        // {A} -> {A, B}: B is added, A stays.
        Reply added = clients.put("/admin/v1/users/" + userId + "/roles", admin, Map.of("roleIds", List.of(roleA, roleB)));
        assertThat(added.status()).as("%s", added).isEqualTo(200);
        assertThat(added.data().path("roles").findValuesAsText("name")).containsExactly(nameA, nameB);
        assertThat(clients.realmRoleNames(keycloakId)).isEqualTo(union(untouched, nameA, nameB));

        // {A, B} -> {B}: A is removed, B stays.
        Reply removed = clients.put("/admin/v1/users/" + userId + "/roles", admin, Map.of("roleIds", List.of(roleB)));
        assertThat(removed.status()).as("%s", removed).isEqualTo(200);
        assertThat(removed.data().path("roles").findValuesAsText("name")).containsExactly(nameB);
        assertThat(clients.realmRoleNames(keycloakId)).isEqualTo(union(untouched, nameB));

        // The same set again is a no-op: no row, nothing moves.
        Reply same = clients.put("/admin/v1/users/" + userId + "/roles", admin, Map.of("roleIds", List.of(roleB)));
        assertThat(same.status()).as("%s", same).isEqualTo(200);
        assertThat(clients.realmRoleNames(keycloakId)).isEqualTo(union(untouched, nameB));

        List<AuditRow> rows = clients.auditRows("USER", UUID.fromString(userId));
        assertThat(rows).extracting(AuditRow::action, AuditRow::outcome).containsExactly(
                tuple("USER_CREATED", "SUCCESS"),
                tuple("USER_ROLES_UPDATED", "SUCCESS"),
                tuple("USER_ROLES_UPDATED", "SUCCESS"));
        assertThat(rows.get(1).afterState().path("roles").findValuesAsText("name")).containsExactly(nameA, nameB);
        assertThat(rows.get(2).afterState().path("roles").findValuesAsText("name")).containsExactly(nameB);
    }

    private static Set<String> union(Set<String> base, String... more) {
        Set<String> all = new HashSet<>(base);
        all.addAll(List.of(more));
        return all;
    }
}
