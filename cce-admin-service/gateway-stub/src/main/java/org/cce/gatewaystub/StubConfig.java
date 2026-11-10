package org.cce.gatewaystub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The stand-in's variables (section 9.1 of the design). Every one is required. */
public record StubConfig(
        int port,
        String databaseUrl,
        String databaseUsername,
        String databasePassword,
        String keycloakServerUrl,
        String keycloakRealm,
        String clientId,
        String clientSecret) {

    public static StubConfig fromEnvironment(Map<String, String> env) {
        List<String> missing = new ArrayList<>();
        String port = required(env, "STUB_PORT", missing);
        String databaseUrl = required(env, "DATABASE_URL", missing);
        String databaseUsername = required(env, "DATABASE_USERNAME", missing);
        String databasePassword = required(env, "DATABASE_PASSWORD", missing);
        String keycloakServerUrl = required(env, "KEYCLOAK_SERVER_URL", missing);
        String keycloakRealm = required(env, "KEYCLOAK_REALM", missing);
        String clientId = required(env, "STUB_CLIENT_ID", missing);
        String clientSecret = required(env, "STUB_CLIENT_SECRET", missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Set the variables " + String.join(", ", missing));
        }
        return new StubConfig(Integer.parseInt(port), databaseUrl, databaseUsername, databasePassword,
                stripTrailingSlash(keycloakServerUrl), keycloakRealm, clientId, clientSecret);
    }

    private static String required(Map<String, String> env, String name, List<String> missing) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            missing.add(name);
            return null;
        }
        return value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
