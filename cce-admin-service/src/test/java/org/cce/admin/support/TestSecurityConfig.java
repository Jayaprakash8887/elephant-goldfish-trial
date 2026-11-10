package org.cce.admin.support;

import org.cce.admin.config.AdminProperties;
import org.cce.admin.security.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * A decoder that trusts {@link TestJwtFactory}'s public key and applies {@link SecurityConfig}'s own
 * validators, so the 401 cases run the real issuer, audience and timestamp checks.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(AdminProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwtFactory.publicKey()).build();
        decoder.setJwtValidator(SecurityConfig.jwtValidator(properties.jwt()));
        return decoder;
    }
}
