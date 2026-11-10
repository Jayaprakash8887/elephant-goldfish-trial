package org.cce.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Section 3, step 4 (B-12). Looks up the local user by the token's {@code sub}; refuses with 403
 * {@code FORBIDDEN} when there is none or it is not {@code ACTIVE}; moves {@code last_login_at}
 * forward to the token's {@code auth_time} (AD-05); and stores the request's {@link CurrentUser}.
 * Passes the request on unchanged unless its authentication is a {@link JwtAuthenticationToken}.
 */
public class CurrentUserFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserFilter.class);
    static final String NO_ACTIVE_USER_MESSAGE = "Your account has no active user in the admin service.";

    private final UserRepository userRepository;
    private final RolesClaimReader rolesClaimReader;
    private final SecurityErrorWriter errorWriter;

    public CurrentUserFilter(UserRepository userRepository, RolesClaimReader rolesClaimReader,
            SecurityErrorWriter errorWriter) {
        this.userRepository = userRepository;
        this.rolesClaimReader = rolesClaimReader;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<CurrentUser> currentUser;
        try {
            currentUser = resolve(jwtAuthentication.getToken());
        } catch (RuntimeException e) {
            // Exceptions thrown in filters never reach the controller advice (section 3).
            log.error("Local-user lookup failed", e);
            errorWriter.writeInternalError(response);
            return;
        }
        if (currentUser.isEmpty()) {
            errorWriter.writeForbidden(response, NO_ACTIVE_USER_MESSAGE);
            return;
        }
        currentUser.get().storeIn(request);
        chain.doFilter(request, response);
    }

    private Optional<CurrentUser> resolve(Jwt jwt) {
        UUID keycloakId = parseUuid(jwt.getSubject());
        if (keycloakId == null) {
            return Optional.empty();
        }
        Optional<UserEntity> found = userRepository.findByKeycloakId(keycloakId);
        if (found.isEmpty() || found.get().getStatus() != UserStatus.ACTIVE) {
            return Optional.empty();
        }
        UserEntity user = found.get();
        Instant authTime = jwt.getClaimAsInstant("auth_time");
        if (authTime != null) {
            userRepository.updateLastLoginIfLater(user.getId(), authTime);
        }
        List<String> roles = rolesClaimReader.read(jwt);
        return Optional.of(new CurrentUser(user.getId(), user.getUsername(), user.getKeycloakId(), roles));
    }

    private static UUID parseUuid(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
