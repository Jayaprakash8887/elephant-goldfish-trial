package org.cce.admin.client;

import java.util.List;
import java.util.UUID;

/**
 * A Keycloak user as the service sees it (section 7). {@code realmRoles} holds the names of the
 * user's direct realm role mappings.
 */
public record KeycloakUserInfo(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean emailVerified,
        List<String> realmRoles) {

    public KeycloakUserInfo {
        realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
    }
}
