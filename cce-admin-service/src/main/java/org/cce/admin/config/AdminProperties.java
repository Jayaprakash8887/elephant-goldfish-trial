package org.cce.admin.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code admin.*} configuration (section 9.4). Every required value is written {@code ${VAR:}} in
 * {@code application.yml}, so a missing variable binds as an empty string and fails
 * {@code @NotBlank} at startup, with a message naming the variable (B-13).
 */
@Validated
@ConfigurationProperties("admin")
public record AdminProperties(
        @Valid @DefaultValue Jwt jwt,
        @Valid @DefaultValue Keycloak keycloak,
        @Valid @DefaultValue Gateway gateway,
        @Valid @DefaultValue Permission permission,
        @Valid @DefaultValue Bootstrap bootstrap) {

    public record Jwt(
            @NotBlank(message = "set JWT_ISSUER") String issuer,
            @NotBlank(message = "set JWT_AUDIENCE") String audience,
            String jwkSetUri,
            String rolesClaimPath) {
    }

    public record Keycloak(
            @NotBlank(message = "set KEYCLOAK_SERVER_URL") String serverUrl,
            @NotBlank(message = "set KEYCLOAK_REALM") String realm,
            String clientId,
            @NotBlank(message = "set KEYCLOAK_ADMIN_CLIENT_SECRET") String clientSecret,
            Duration connectTimeout,
            Duration readTimeout,
            Integer actionEmailLifespan,
            String actionEmailClientId,
            String actionEmailRedirectUri) {
    }

    public record Gateway(
            @NotBlank(message = "set GATEWAY_BASE_URL") String baseUrl,
            String syncPath,
            Duration connectTimeout,
            Duration readTimeout) {
    }

    /** AD-17. {@code application.yml} defaults it to {@code true}; so does an absent block. */
    public record Permission(@DefaultValue("true") boolean checkEnabled) {
    }

    public record Bootstrap(String adminUsername) {
    }
}
