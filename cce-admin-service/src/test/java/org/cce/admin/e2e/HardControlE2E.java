package org.cce.admin.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.cce.admin.e2e.E2eClients.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The live hard control (G9, section 10): a console user whose only role holds just the implicit
 * rows gets 403 {@code FORBIDDEN} on {@code GET /users} and 200 on {@code GET /me/menus}. This checks
 * the service's own permission check (section 3, step 5), not the gateway's.
 */
class HardControlE2E {

    private final E2eEnvironment env = E2eEnvironment.get();
    private final E2eClients clients = E2eClients.get();

    @AfterEach
    void resetStub() {
        StubMode.reset();
    }

    @Test
    void consoleUserIsRefusedTheAdminApiButGetsItsMenus() {
        String admin = TokenClient.adminToken();
        String name = env.roleName("HC");
        JsonNode role = clients.createSyncedRole(admin, name);

        // Only the two implicit rows.
        Reply permissions = clients.get("/admin/v1/roles/" + role.path("id").asText() + "/permissions", admin);
        assertThat(permissions.status()).as("%s", permissions).isEqualTo(200);
        assertThat(permissions.data().path("permissions").findValuesAsText("implicit")).containsExactly("true", "true");

        JsonNode user = clients.createUser(admin, "hc", List.of(role.path("id").asText())).data();
        String token = clients.signIn(user);

        Reply users = clients.get("/admin/v1/users", token);
        assertThat(users.status()).as("%s", users).isEqualTo(403);
        assertThat(users.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(users.body().path("error").path("message").asText())
                .isEqualTo("Your roles do not allow GET /admin/v1/users.");

        Reply menus = clients.get("/admin/v1/me/menus", token);
        assertThat(menus.status()).as("%s", menus).isEqualTo(200);
        assertThat(menus.data().isArray()).isTrue();
        assertThat(menus.data()).isEmpty();
    }
}
