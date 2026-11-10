package org.cce.admin.client;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * The service's only access to Keycloak (section 7). A 404 on a lookup returns empty. Every other
 * failure raises {@link KeycloakConflictException} (409) or {@link KeycloakCallFailedException}.
 * Service tests replace this interface with a mock (PRD §9 Testability).
 */
public interface KeycloakGateway {

    /** All realm role names, in one call. Used by 5.1 and 5.4. */
    Set<String> listRealmRoleNames();

    /** The role representation, or empty when the realm role does not exist. */
    Optional<RoleRepresentation> findRealmRole(String name);

    /** Whether any Keycloak user holds the realm role. Used by 5.9. */
    boolean hasRealmRoleMembers(String name);

    /** Exact-match lookups by username and by email, merged. Used by 5.1. */
    List<KeycloakUserInfo> findUsersByUsernameOrEmail(String username, String email);

    /** Exact lookup by username. Used by 5.1 after a 409, and by 5.13. */
    Optional<KeycloakUserInfo> findUserByUsername(String username);

    Optional<KeycloakUserInfo> getUser(UUID keycloakUserId);

    /** The realm's {@code verifyEmail} setting. */
    boolean isEmailVerificationRequired();

    /** Creates an enabled user with {@code emailVerified = false}, and returns its id. */
    UUID createUser(String username, String email, String firstName, String lastName, List<String> requiredActions);

    void addRealmRoles(UUID keycloakUserId, Collection<String> roleNames);

    void removeRealmRoles(UUID keycloakUserId, Collection<String> roleNames);

    void updateProfile(UUID keycloakUserId, String email, String firstName, String lastName, boolean emailVerified);

    void setEnabled(UUID keycloakUserId, boolean enabled);

    void logoutAllSessions(UUID keycloakUserId);

    void sendExecuteActionsEmail(UUID keycloakUserId, List<String> actions);
}
