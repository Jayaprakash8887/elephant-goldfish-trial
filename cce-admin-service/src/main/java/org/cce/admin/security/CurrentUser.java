package org.cce.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The request-scoped caller, stored by {@link CurrentUserFilter} as a request attribute (section 3,
 * step 4): the local user id, username, {@code keycloak_id} and the token's role names (step 3).
 */
public record CurrentUser(UUID id, String username, UUID keycloakId, List<String> roleNames) {

    static final String REQUEST_ATTRIBUTE = CurrentUser.class.getName();

    public CurrentUser {
        roleNames = List.copyOf(roleNames);
    }

    void storeIn(HttpServletRequest request) {
        request.setAttribute(REQUEST_ATTRIBUTE, this);
    }

    static Optional<CurrentUser> from(HttpServletRequest request) {
        return Optional.ofNullable((CurrentUser) request.getAttribute(REQUEST_ATTRIBUTE));
    }

    /** The current request's caller, or empty when there is no request or no authenticated caller. */
    public static Optional<CurrentUser> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                (CurrentUser) attributes.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }
}
