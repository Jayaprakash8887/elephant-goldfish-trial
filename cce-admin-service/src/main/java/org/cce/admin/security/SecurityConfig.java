package org.cce.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.cce.admin.config.AdminProperties;
import org.cce.admin.repository.ApiPermissionRepository;
import org.cce.admin.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** The request pipeline of section 3 (B-05, B-10 to B-13). */
@Configuration
public class SecurityConfig {

    /** Allowed clock skew for {@code exp} and {@code nbf} (section 3, step 2). */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** Step 1: paths that need no token and skip steps 2 to 5. */
    static final RequestMatcher PUBLIC_PATHS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/admin/v1/health"),
            PathPatternRequestMatcher.withDefaults().matcher("/admin/v1/api-docs/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/admin/v1/swagger-ui/**"));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AdminProperties properties,
            UserRepository userRepository, ApiPermissionRepository permissionRepository,
            RolesClaimReader rolesClaimReader, ObjectMapper objectMapper) throws Exception {
        SecurityErrorWriter errorWriter = new SecurityErrorWriter(objectMapper);
        CurrentUserFilter currentUserFilter = new CurrentUserFilter(userRepository, rolesClaimReader, errorWriter);
        PermissionCheckFilter permissionCheckFilter = new PermissionCheckFilter(
                permissionRepository, errorWriter, properties.permission().checkEnabled());

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errorWriter)
                        .accessDeniedHandler(errorWriter))
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(publicPathsIgnoringResolver())
                        .authenticationEntryPoint(errorWriter)
                        .jwt(Customizer.withDefaults()))
                .addFilterAfter(currentUserFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(permissionCheckFilter, CurrentUserFilter.class);
        return http.build();
    }

    /** Step 2: signature from {@code JWT_JWK_SET_URI}, with {@link #jwtValidator} applied. */
    @Bean
    JwtDecoder jwtDecoder(AdminProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwt().jwkSetUri()).build();
        decoder.setJwtValidator(jwtValidator(properties.jwt()));
        return decoder;
    }

    @Bean
    RolesClaimReader rolesClaimReader(AdminProperties properties) {
        return new RolesClaimReader(properties.jwt().rolesClaimPath());
    }

    /**
     * Step 2's validators: expiry and not-before with 30 s of skew, the issuer, and an {@code aud}
     * that contains {@code JWT_AUDIENCE}. Also used by the test decoder, so tests run the real checks.
     */
    public static OAuth2TokenValidator<Jwt> jwtValidator(AdminProperties.Jwt jwt) {
        String audience = jwt.audience();
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(jwt.issuer()),
                new JwtClaimValidator<List<String>>("aud", a -> a != null && a.contains(audience)));
    }

    /**
     * Public paths skip steps 2 to 5 (step 1), so a bearer header sent to them is not decoded.
     * Every other path resolves the token as Spring does by default.
     */
    private static BearerTokenResolver publicPathsIgnoringResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> PUBLIC_PATHS.matches(request) ? null : delegate.resolve(request);
    }
}
