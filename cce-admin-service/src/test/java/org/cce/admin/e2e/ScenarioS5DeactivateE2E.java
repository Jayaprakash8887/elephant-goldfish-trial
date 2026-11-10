package org.cce.admin.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.cce.admin.e2e.E2eClients.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S5 (CUJ 5): an administrator deactivates and re-activates a user. {@code INACTIVE} disables the
 * Keycloak account, keeps {@code user_roles}, and the user's token gets 403; {@code ACTIVE} restores
 * access with the kept roles.
 */
class ScenarioS5DeactivateE2E {

    private final E2eEnvironment env = E2eEnvironment.get();
    private final E2eClients clients = E2eClients.get();

    @AfterEach
    void resetStub() {
        StubMode.reset();
    }

    @Test
    void deactivationBlocksTheUserAndReactivationRestoresAccess() {
        String admin = TokenClient.adminToken();
        String roleName = env.roleName("S5");
        String roleId = clients.createSyncedRole(admin, roleName).path("id").asText();
        JsonNode user = clients.createUser(admin, "s5", List.of(roleId)).data();
        String userId = user.path("id").asText();
        UUID keycloakId = UUID.fromString(user.path("keycloakId").asText());
        String tokenBefore = clients.signIn(user);

        Reply allowed = clients.get("/admin/v1/me", tokenBefore);
        assertThat(allowed.status()).as("%s", allowed).isEqualTo(200);

        // INACTIVE: the Keycloak account is disabled, user_roles stays, the token gets 403.
        Reply deactivated = clients.patch("/admin/v1/users/" + userId + "/status", admin, Map.of("status", "INACTIVE"));
        assertThat(deactivated.status()).as("%s", deactivated).isEqualTo(200);
        assertThat(deactivated.data().path("status").asText()).isEqualTo("INACTIVE");
        assertThat(clients.isEnabled(keycloakId)).isFalse();
        Reply kept = clients.get("/admin/v1/users/" + userId, admin);
        assertThat(kept.data().path("roles").findValuesAsText("name")).containsExactly(roleName);
        assertThat(clients.realmRoleNames(keycloakId)).contains(roleName);

        Reply refused = clients.get("/admin/v1/me", tokenBefore);
        assertThat(refused.status()).as("%s", refused).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("FORBIDDEN");
        Reply refusedMenus = clients.get("/admin/v1/me/menus", tokenBefore);
        assertThat(refusedMenus.status()).as("%s", refusedMenus).isEqualTo(403);

        // ACTIVE: the account is enabled again, and a new sign-in carries the kept role.
        Reply activated = clients.patch("/admin/v1/users/" + userId + "/status", admin, Map.of("status", "ACTIVE"));
        assertThat(activated.status()).as("%s", activated).isEqualTo(200);
        assertThat(activated.data().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(activated.data().path("roles").findValuesAsText("name")).containsExactly(roleName);
        assertThat(clients.isEnabled(keycloakId)).isTrue();

        String tokenAfter = clients.signIn(user);
        Reply me = clients.get("/admin/v1/me", tokenAfter);
        assertThat(me.status()).as("%s", me).isEqualTo(200);
        assertThat(me.data().path("roles").get(0).asText()).isEqualTo(roleName);
        assertThat(me.data().path("roles")).hasSize(1);
        Reply menus = clients.get("/admin/v1/me/menus", tokenAfter);
        assertThat(menus.status()).as("%s", menus).isEqualTo(200);
    }
}
