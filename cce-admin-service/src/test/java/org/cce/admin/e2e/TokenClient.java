package org.cce.admin.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** Gets password-grant tokens from the public client {@code cce-e2e} (section 9.2). */
public final class TokenClient {

    private static final RestClient HTTP =
            RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();

    private TokenClient() {
    }

    /** The access token of {@code username}. Fails when Keycloak refuses the grant. */
    public static String passwordToken(String username, String password) {
        E2eEnvironment env = E2eEnvironment.get();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", E2eEnvironment.E2E_CLIENT_ID);
        form.add("username", username);
        form.add("password", password);
        JsonNode body = HTTP.post().uri(env.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String token = body == null ? null : body.path("access_token").asText(null);
        if (token == null) {
            throw new IllegalStateException("the token response for " + username + " has no access_token");
        }
        return token;
    }

    /** The test administrator's token ({@code CCE_TEST_ADMIN_USERNAME}, {@code CCE_TEST_ADMIN_PASSWORD}). */
    public static String adminToken() {
        E2eEnvironment env = E2eEnvironment.get();
        return passwordToken(env.value("CCE_TEST_ADMIN_USERNAME"), env.value("CCE_TEST_ADMIN_PASSWORD"));
    }
}
