package org.cce.gatewaystub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link RealmRoles} over the Keycloak Admin REST API with {@code java.net.http}, authenticated with
 * the stand-in client's own client-credentials token.
 */
public class HttpRealmRoles implements RealmRoles {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, List<String>>> ATTRIBUTES = new TypeReference<>() {
    };

    private final StubConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public HttpRealmRoles(StubConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public List<RealmRole> list() {
        JsonNode roles = readJson(send(admin("/roles?briefRepresentation=false").GET(), 200));
        List<RealmRole> result = new ArrayList<>();
        for (JsonNode role : roles) {
            result.add(toRole(role));
        }
        return result;
    }

    @Override
    public void create(String name) {
        ObjectNode role = objectMapper.createObjectNode();
        role.put("name", name);
        role.putObject("attributes").putArray(MANAGED_ATTRIBUTE).add("true");
        send(admin("/roles").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(role.toString())), 201);
        // Some Keycloak versions drop attributes on create: set the tag with PUT when it is missing.
        JsonNode created = readJson(send(admin("/roles/" + encode(name)).GET(), 200));
        if (!toRole(created).managed()) {
            ObjectNode update = created.deepCopy();
            ObjectNode attributes = update.has("attributes") && update.get("attributes").isObject()
                    ? (ObjectNode) update.get("attributes")
                    : update.putObject("attributes");
            attributes.putArray(MANAGED_ATTRIBUTE).add("true");
            send(admin("/roles/" + encode(name)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(update.toString())), 204);
        }
    }

    @Override
    public void delete(String name) {
        send(admin("/roles/" + encode(name)).DELETE(), 204);
    }

    private RealmRole toRole(JsonNode role) {
        Map<String, List<String>> attributes = role.hasNonNull("attributes")
                ? objectMapper.convertValue(role.get("attributes"), ATTRIBUTES)
                : Map.of();
        return new RealmRole(role.path("name").asText(), attributes);
    }

    private HttpRequest.Builder admin(String path) {
        return HttpRequest.newBuilder(URI.create(config.keycloakServerUrl() + "/admin/realms/"
                        + encode(config.keycloakRealm()) + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken());
    }

    private synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(config.clientId())
                + "&client_secret=" + encode(config.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.keycloakServerUrl() + "/realms/"
                        + encode(config.keycloakRealm()) + "/protocol/openid-connect/token"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        JsonNode response = readJson(execute(request, 200));
        token = response.path("access_token").asText();
        long expiresIn = response.path("expires_in").asLong(60);
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(0, expiresIn - 10));
        return token;
    }

    private String send(HttpRequest.Builder request, int expectedStatus) {
        return execute(request.build(), expectedStatus);
    }

    private String execute(HttpRequest request, int expectedStatus) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri().getPath() + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
        if (response.statusCode() != expectedStatus) {
            throw new IllegalStateException(request.method() + " " + request.uri().getPath() + " returned "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak returned invalid JSON", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
