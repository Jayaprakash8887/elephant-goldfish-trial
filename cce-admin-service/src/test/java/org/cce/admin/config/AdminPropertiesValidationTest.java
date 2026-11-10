package org.cce.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * A missing required variable fails startup with a message naming it (B-13). The properties are
 * written as in {@code application.yml}, {@code ${VAR:}}, so a missing variable binds as "".
 */
class AdminPropertiesValidationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminProperties.class)
    static class PropertiesOnly {
    }

    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "admin.jwt.issuer", "${JWT_ISSUER:}",
            "admin.jwt.audience", "${JWT_AUDIENCE:}",
            "admin.keycloak.server-url", "${KEYCLOAK_SERVER_URL:}",
            "admin.keycloak.realm", "${KEYCLOAK_REALM:}",
            "admin.keycloak.client-secret", "${KEYCLOAK_ADMIN_CLIENT_SECRET:}",
            "admin.gateway.base-url", "${GATEWAY_BASE_URL:}");

    private static final Map<String, String> VARIABLES = Map.of(
            "JWT_ISSUER", "http://issuer.test/realms/cce",
            "JWT_AUDIENCE", "gateway-service",
            "KEYCLOAK_SERVER_URL", "http://keycloak.test/auth",
            "KEYCLOAK_REALM", "cce",
            "KEYCLOAK_ADMIN_CLIENT_SECRET", "test-only",
            "GATEWAY_BASE_URL", "http://gateway.test");

    private ApplicationContextRunner runnerWithout(String missingVariable) {
        List<String> values = new ArrayList<>();
        PLACEHOLDERS.forEach((key, value) -> values.add(key + "=" + value));
        Map<String, String> variables = new LinkedHashMap<>(VARIABLES);
        variables.remove(missingVariable);
        variables.forEach((key, value) -> values.add(key + "=" + value));
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(values.toArray(String[]::new));
    }

    @Test
    void startsWhenEveryRequiredVariableIsSet() {
        runnerWithout("NONE").run(context -> {
            assertThat(context).hasNotFailed();
            AdminProperties properties = context.getBean(AdminProperties.class);
            assertThat(properties.jwt().issuer()).isEqualTo("http://issuer.test/realms/cce");
            assertThat(properties.permission().checkEnabled()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"JWT_ISSUER", "KEYCLOAK_REALM", "GATEWAY_BASE_URL"})
    void aMissingRequiredVariableFailsStartupNamingIt(String variable) {
        runnerWithout(variable).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("set " + variable);
        });
    }
}
