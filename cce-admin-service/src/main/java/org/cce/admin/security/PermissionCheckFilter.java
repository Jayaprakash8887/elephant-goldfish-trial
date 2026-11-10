package org.cce.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.cce.admin.entity.ApiPermissionEntity;
import org.cce.admin.repository.ApiPermissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Section 3, step 5 (AD-17). One query for the caller's rows for the request method, using
 * {@code idx_permission_name}, matched against the request path with {@link AntPathMatcher} in its
 * default settings. No cache. Skipped when {@code PERMISSION_CHECK_ENABLED} is false. Passes the
 * request on unchanged unless its authentication is a {@link JwtAuthenticationToken}.
 */
public class PermissionCheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PermissionCheckFilter.class);

    private final ApiPermissionRepository permissionRepository;
    private final SecurityErrorWriter errorWriter;
    private final boolean enabled;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public PermissionCheckFilter(ApiPermissionRepository permissionRepository, SecurityErrorWriter errorWriter,
            boolean enabled) {
        this.permissionRepository = permissionRepository;
        this.errorWriter = errorWriter;
        this.enabled = enabled;
        log.info("PERMISSION_CHECK_ENABLED={}", enabled);
        if (!enabled) {
            log.warn("The service's own permission check is off: only the gateway now enforces permissions.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || !(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean allowed;
        try {
            allowed = isAllowed(request, method, path);
        } catch (RuntimeException e) {
            // Exceptions thrown in filters never reach the controller advice (section 3).
            log.error("Permission check failed", e);
            errorWriter.writeInternalError(response);
            return;
        }
        if (!allowed) {
            errorWriter.writeForbidden(response, "Your roles do not allow " + method + " " + path + ".");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowed(HttpServletRequest request, String method, String path) {
        List<String> roles = CurrentUser.from(request).map(CurrentUser::roleNames).orElse(List.of());
        if (roles.isEmpty()) {
            return false;
        }
        for (ApiPermissionEntity row : permissionRepository.findByPermissionNameInAndHttpMethod(roles, method)) {
            if (matcher.match(row.getUriPattern(), path)) {
                return true;
            }
        }
        return false;
    }
}
