package org.cce.admin.config;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Keycloak admin client (section 7): the client-credentials grant of
 * {@code KEYCLOAK_ADMIN_CLIENT_ID}, with an HTTP client built the way the library builds its own,
 * plus the configured timeouts. Registering the library's {@link JacksonProvider} keeps its
 * tolerance of unknown JSON fields from the newer server.
 */
@Configuration
public class KeycloakClientConfig {

    @Bean
    Keycloak keycloak(AdminProperties properties) {
        AdminProperties.Keycloak settings = properties.keycloak();
        Client resteasyClient = ((ResteasyClientBuilder) ClientBuilder.newBuilder())
                .connectionPoolSize(10)
                .connectTimeout(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(settings.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .register(new JacksonProvider(), 100)
                .build();
        return KeycloakBuilder.builder()
                .serverUrl(settings.serverUrl())
                .realm(settings.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(settings.clientId())
                .clientSecret(settings.clientSecret())
                .resteasyClient(resteasyClient)
                .build();
    }

    /**
     * The service's own access token, from the admin client's token manager, so the gateway sync
     * shares its token cache and its authentication mode (AD-13).
     */
    @Bean
    Supplier<String> serviceAccessToken(Keycloak keycloak) {
        return () -> keycloak.tokenManager().getAccessTokenString();
    }
}
