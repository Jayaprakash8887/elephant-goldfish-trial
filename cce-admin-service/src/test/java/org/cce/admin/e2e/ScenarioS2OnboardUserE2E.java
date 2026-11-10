package org.cce.admin.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.cce.admin.e2e.E2eClients.AuditRow;
import org.cce.admin.e2e.E2eClients.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S2 (CUJ 2): an administrator onboards a user in one call. A new user is created with its
 * Keycloak account and realm roles, the local row, {@code USER_CREATED} and the set-password email;
 * a Keycloak-only account is linked ({@code after.linked = true}, AD-18).
 */
class ScenarioS2OnboardUserE2E {

    private final E2eEnvironment env = E2eEnvironment.get();
    private final E2eClients clients = E2eClients.get();

    @AfterEach
    void resetStub() {
        StubMode.reset();
    }

    @Test
    void createsANewUserAndLinksAKeycloakOnlyAccount() throws InterruptedException {
        String admin = TokenClient.adminToken();
        JsonNode roleA = clients.createSyncedRole(admin, env.roleName("S2A"));
        JsonNode roleB = clients.createSyncedRole(admin, env.roleName("S2B"));

        // A new user with two roles.
        Reply created = clients.createUser(admin, "s2a",
                List.of(roleA.path("id").asText(), roleB.path("id").asText()));
        JsonNode user = created.data();
        assertThat(created.body().has("warnings")).as("no ACTION_EMAIL_NOT_SENT: %s", created).isFalse();
        assertThat(created.headers().getLocation()).hasToString("/admin/v1/users/" + user.path("id").asText());
        assertThat(user.path("status").asText()).isEqualTo("ACTIVE");
        UUID keycloakId = UUID.fromString(user.path("keycloakId").asText());

        // The Keycloak account and its realm roles.
        assertThat(clients.isEnabled(keycloakId)).isTrue();
        assertThat(clients.realmRoleNames(keycloakId)).contains(env.roleName("S2A"), env.roleName("S2B"));

        // The local row, with user_roles.
        Reply local = clients.get("/admin/v1/users/" + user.path("id").asText(), admin);
        assertThat(local.status()).as("%s", local).isEqualTo(200);
        assertThat(local.data().path("username").asText()).isEqualTo(env.username("s2a"));
        assertThat(local.data().path("roles").findValuesAsText("name"))
                .containsExactly(env.roleName("S2A"), env.roleName("S2B"));

        // One USER_CREATED row, not linked.
        List<AuditRow> rows = clients.auditRows("USER", UUID.fromString(user.path("id").asText()));
        assertThat(rows).extracting(AuditRow::action, AuditRow::outcome).containsExactly(
                tuple("USER_CREATED", "SUCCESS"));
        assertThat(rows.get(0).afterState().path("linked").asBoolean(true)).isFalse();

        // The set-password email in Mailpit.
        assertThat(awaitMessagesTo(env.email("s2a"))).isNotEmpty();

        // A Keycloak-only account (enabled, no local user) is linked, not refused.
        UUID existingId = clients.createKeycloakOnlyAccount(env.username("s2b"), env.email("s2b"), "E2E", "S2B");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", env.username("s2b"));
        body.put("email", env.email("s2b"));
        body.put("firstName", "E2E");
        body.put("lastName", "S2B");
        body.put("roleIds", List.of(roleA.path("id").asText()));
        Reply linked = clients.post("/admin/v1/users", admin, body);
        assertThat(linked.status()).as("%s", linked).isEqualTo(201);
        assertThat(linked.data().path("keycloakId").asText()).isEqualTo(existingId.toString());
        assertThat(clients.realmRoleNames(existingId)).contains(env.roleName("S2A"));

        List<AuditRow> linkedRows = clients.auditRows("USER", UUID.fromString(linked.data().path("id").asText()));
        assertThat(linkedRows).extracting(AuditRow::action, AuditRow::outcome).containsExactly(
                tuple("USER_CREATED", "SUCCESS"));
        assertThat(linkedRows.get(0).afterState().path("linked").asBoolean(false)).isTrue();
    }

    private List<JsonNode> awaitMessagesTo(String address) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        List<JsonNode> messages = clients.mailpitMessagesTo(address);
        while (messages.isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(500);
            messages = clients.mailpitMessagesTo(address);
        }
        return messages;
    }
}
