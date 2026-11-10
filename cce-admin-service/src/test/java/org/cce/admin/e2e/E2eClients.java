package org.cce.admin.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The clients of the E2E checks (Phase 8): one {@link RestClient} each for the service, the
 * stand-in and Mailpit, and a {@code keycloak-admin-client} instance for the local master
 * {@code admin}, used only for test-only setup: setting a password and clearing required actions,
 * creating a Keycloak-only account, and reading a user's {@code enabled} flag and realm roles.
 */
public final class E2eClients {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final E2eClients INSTANCE = new E2eClients(E2eEnvironment.get());

    private final E2eEnvironment env;
    private final RestClient service;
    private final RestClient stub;
    private final RestClient mailpit;
    private Keycloak keycloakAdmin;

    /** A response of the service: status, headers and the JSON body ({@code NullNode} when empty). */
    public record Reply(int status, HttpHeaders headers, JsonNode body) {

        public JsonNode data() {
            return body.path("data");
        }

        public String errorCode() {
            return body.path("error").path("code").asText(null);
        }

        @Override
        public String toString() {
            return "HTTP " + status + " " + body;
        }
    }

    /** One {@code audit_log} row, as the checks read it. */
    public record AuditRow(UUID id, String action, String outcome, JsonNode afterState, JsonNode failure) {
    }

    private E2eClients(E2eEnvironment env) {
        this.env = env;
        this.service = restClient(env.serviceUrl());
        this.stub = restClient(env.stubUrl());
        this.mailpit = restClient(env.mailpitUrl());
    }

    public static E2eClients get() {
        return INSTANCE;
    }

    private static RestClient restClient(String baseUrl) {
        // The JDK client, so PATCH works.
        return RestClient.builder().baseUrl(baseUrl).requestFactory(new JdkClientHttpRequestFactory()).build();
    }

    // ------------------------------------------------------------------------------ the service

    /** Calls the service with a bearer token and an optional JSON body. Never throws on a status. */
    public Reply call(HttpMethod method, String path, String token, Object body) {
        RestClient.RequestBodySpec spec = service.method(method).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> new Reply(response.getStatusCode().value(),
                response.getHeaders(), parse(response.getBody().readAllBytes())), true);
    }

    public Reply get(String path, String token) {
        return call(HttpMethod.GET, path, token, null);
    }

    public Reply post(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, body);
    }

    public Reply put(String path, String token, Object body) {
        return call(HttpMethod.PUT, path, token, body);
    }

    public Reply patch(String path, String token, Object body) {
        return call(HttpMethod.PATCH, path, token, body);
    }

    private static JsonNode parse(byte[] bytes) {
        if (bytes.length == 0) {
            return NullNode.getInstance();
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("the response is not JSON: " + new String(bytes), e);
        }
    }

    // ----------------------------------------------------------------------- stand-in, Mailpit

    public RestClient stub() {
        return stub;
    }

    /** The messages Mailpit holds for {@code address} ({@code GET /api/v1/messages}). */
    public List<JsonNode> mailpitMessagesTo(String address) {
        JsonNode list = parse(mailpit.get().uri("/api/v1/messages?limit=500").retrieve().body(byte[].class));
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode message : list.path("messages")) {
            for (JsonNode to : message.path("To")) {
                if (address.equalsIgnoreCase(to.path("Address").asText())) {
                    found.add(message);
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------- Keycloak, test-only setup (master admin)

    private synchronized Keycloak keycloakAdmin() {
        if (keycloakAdmin == null) {
            keycloakAdmin = KeycloakBuilder.builder()
                    .serverUrl(env.keycloakUrl())
                    .realm("master")
                    .grantType(OAuth2Constants.PASSWORD)
                    .clientId("admin-cli")
                    .username(E2eEnvironment.KEYCLOAK_MASTER_ADMIN)
                    .password(env.value("KEYCLOAK_ADMIN_PASSWORD"))
                    .build();
        }
        return keycloakAdmin;
    }

    private RealmResource realm() {
        return keycloakAdmin().realm(env.realm());
    }

    /**
     * Test-only: sets a non-temporary password and clears the required actions, so the password
     * grant works for a user the service created with {@code UPDATE_PASSWORD} pending (section 10).
     */
    public void setPasswordAndClearRequiredActions(UUID keycloakUserId, String password) {
        UserResource user = realm().users().get(keycloakUserId.toString());
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        user.resetPassword(credential);
        UserRepresentation representation = user.toRepresentation();
        representation.setRequiredActions(List.of());
        user.update(representation);
    }

    /** Test-only: an enabled Keycloak account with no local user (S2, AD-18). Returns its id. */
    public UUID createKeycloakOnlyAccount(String username, String email, String firstName, String lastName) {
        UserRepresentation representation = new UserRepresentation();
        representation.setUsername(username);
        representation.setEmail(email);
        representation.setFirstName(firstName);
        representation.setLastName(lastName);
        representation.setEnabled(true);
        try (Response response = realm().users().create(representation)) {
            return UUID.fromString(CreatedResponseUtil.getCreatedId(response));
        }
    }

    public boolean isEnabled(UUID keycloakUserId) {
        return Boolean.TRUE.equals(realm().users().get(keycloakUserId.toString()).toRepresentation().isEnabled());
    }

    /** The user's direct realm role mappings, by name. */
    public Set<String> realmRoleNames(UUID keycloakUserId) {
        return realm().users().get(keycloakUserId.toString()).roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    /** The realm role with its attributes, or empty when it does not exist. */
    public Optional<RoleRepresentation> realmRole(String name) {
        try {
            return Optional.of(realm().roles().get(name).toRepresentation());
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    // --------------------------------------------------------------------------- audit_log rows

    /**
     * The {@code audit_log} rows of one entity, oldest first.
     *
     * <p>DESIGN-GAP: Phase 8 lists no database access for the E2E clients, but S2, S3 and S6 check
     * audit rows, and the service has no audit read endpoint (Non-Goals). The rows are read with
     * plain JDBC through the host-run {@code DATABASE_URL}, {@code DATABASE_USERNAME} and
     * {@code DATABASE_PASSWORD} of {@code .env} (section 9.3). Read-only.
     */
    public List<AuditRow> auditRows(String entityType, UUID entityId) {
        String sql = "SELECT id, action, outcome, after_state::text, failure::text FROM audit_log "
                + "WHERE entity_type = ? AND entity_id = ? ORDER BY occurred_at, id";
        try (Connection connection = DriverManager.getConnection(env.value("DATABASE_URL"),
                env.value("DATABASE_USERNAME"), env.value("DATABASE_PASSWORD"));
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setObject(2, entityId);
            List<AuditRow> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new AuditRow(resultSet.getObject(1, UUID.class), resultSet.getString(2),
                            resultSet.getString(3), parseText(resultSet.getString(4)),
                            parseText(resultSet.getString(5))));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read audit_log", e);
        }
    }

    // ------------------------------------------------------- admin API steps shared by the scenarios

    /** {@code POST /roles} as the administrator. Expects 201 and returns the Role. */
    public JsonNode createRole(String adminToken, String name) {
        Reply reply = post("/admin/v1/roles", adminToken,
                Map.of("name", name, "displayName", "E2E role " + name));
        if (reply.status() != 201) {
            throw new AssertionError("POST /roles for " + name + ": expected 201, got " + reply);
        }
        return reply.data();
    }

    /** {@code POST /roles}, then checks that the first sync made it {@code SYNCED}. */
    public JsonNode createSyncedRole(String adminToken, String name) {
        JsonNode role = createRole(adminToken, name);
        if (!"SYNCED".equals(role.path("syncStatus").asText())) {
            throw new AssertionError("role " + name + " is not SYNCED after create: " + role);
        }
        return role;
    }

    /** {@code POST /users} as the administrator. Expects 201 and returns the reply. */
    public Reply createUser(String adminToken, String scenario, List<String> roleIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", env.username(scenario));
        body.put("email", env.email(scenario));
        body.put("firstName", "E2E");
        body.put("lastName", scenario.toUpperCase(java.util.Locale.ROOT));
        body.put("roleIds", roleIds);
        Reply reply = post("/admin/v1/users", adminToken, body);
        if (reply.status() != 201) {
            throw new AssertionError("POST /users for " + env.username(scenario) + ": expected 201, got " + reply);
        }
        return reply;
    }

    /**
     * Test-only: sets a random password on the user's Keycloak account and clears its required
     * actions (section 10), then returns the user's password-grant token.
     */
    public String signIn(JsonNode user) {
        String password = "E2e-" + UUID.randomUUID();
        setPasswordAndClearRequiredActions(UUID.fromString(user.path("keycloakId").asText()), password);
        return TokenClient.passwordToken(user.path("username").asText(), password);
    }

    private static JsonNode parseText(String text) {
        return text == null ? NullNode.getInstance() : parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
