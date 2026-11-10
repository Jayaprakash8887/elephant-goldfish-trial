package org.cce.admin.client;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.cce.admin.config.AdminProperties;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

/**
 * {@link KeycloakGateway} on {@code keycloak-admin-client} (section 7). A 404 on a lookup returns
 * empty; a 409 raises {@link KeycloakConflictException}; every other failure, including a timeout or
 * a failure to get the service token, raises {@link KeycloakCallFailedException}.
 */
@Component
public class KeycloakAdminClientGateway implements KeycloakGateway {

    private final Keycloak keycloak;
    private final AdminProperties.Keycloak settings;

    public KeycloakAdminClientGateway(Keycloak keycloak, AdminProperties properties) {
        this.keycloak = keycloak;
        this.settings = properties.keycloak();
    }

    @Override
    public Set<String> listRealmRoleNames() {
        return call("list realm roles", () -> realm().roles().list().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public Optional<RoleRepresentation> findRealmRole(String name) {
        return lookup("find realm role " + name, () -> realm().roles().get(name).toRepresentation());
    }

    @Override
    public boolean hasRealmRoleMembers(String name) {
        // A realm role that does not exist has no members (a 404 on a lookup is "not found").
        return lookup("list members of realm role " + name,
                () -> !realm().roles().get(name).getUserMembers(0, 1).isEmpty())
                .orElse(false);
    }

    @Override
    public List<KeycloakUserInfo> findUsersByUsernameOrEmail(String username, String email) {
        return call("find users by username or email", () -> {
            Map<String, UserRepresentation> merged = new LinkedHashMap<>();
            for (UserRepresentation user : realm().users().searchByUsername(username, true)) {
                merged.putIfAbsent(user.getId(), user);
            }
            for (UserRepresentation user : realm().users().searchByEmail(email, true)) {
                merged.putIfAbsent(user.getId(), user);
            }
            return merged.values().stream().map(this::toInfo).toList();
        });
    }

    @Override
    public Optional<KeycloakUserInfo> findUserByUsername(String username) {
        return call("find user " + username, () -> realm().users().searchByUsername(username, true).stream()
                .filter(user -> username.equalsIgnoreCase(user.getUsername()))
                .findFirst()
                .map(this::toInfo));
    }

    @Override
    public Optional<KeycloakUserInfo> getUser(UUID keycloakUserId) {
        return lookup("get user " + keycloakUserId, () -> toInfo(user(keycloakUserId).toRepresentation()));
    }

    @Override
    public boolean isEmailVerificationRequired() {
        return call("read realm settings", () -> Boolean.TRUE.equals(realm().toRepresentation().isVerifyEmail()));
    }

    @Override
    public UUID createUser(String username, String email, String firstName, String lastName,
            List<String> requiredActions) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setRequiredActions(List.copyOf(requiredActions));
        return call("create user " + username, () -> {
            try (Response response = realm().users().create(user)) {
                if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                    throw new KeycloakConflictException("Keycloak refused to create user " + username + ": 409", null);
                }
                return UUID.fromString(CreatedResponseUtil.getCreatedId(response));
            }
        });
    }

    @Override
    public void addRealmRoles(UUID keycloakUserId, Collection<String> roleNames) {
        run("add realm roles to " + keycloakUserId, () -> {
            Map<String, RoleRepresentation> byName = realmRolesByName();
            List<RoleRepresentation> roles = roleNames.stream().map(name -> {
                RoleRepresentation role = byName.get(name);
                if (role == null) {
                    throw new KeycloakCallFailedException("Realm role " + name + " does not exist", null, null);
                }
                return role;
            }).toList();
            if (!roles.isEmpty()) {
                user(keycloakUserId).roles().realmLevel().add(roles);
            }
        });
    }

    @Override
    public void removeRealmRoles(UUID keycloakUserId, Collection<String> roleNames) {
        run("remove realm roles from " + keycloakUserId, () -> {
            Map<String, RoleRepresentation> byName = realmRolesByName();
            // A role whose realm role no longer exists is skipped (AD-10).
            List<RoleRepresentation> roles = roleNames.stream().map(byName::get).filter(r -> r != null).toList();
            if (!roles.isEmpty()) {
                user(keycloakUserId).roles().realmLevel().remove(roles);
            }
        });
    }

    @Override
    public void updateProfile(UUID keycloakUserId, String email, String firstName, String lastName,
            boolean emailVerified) {
        run("update user " + keycloakUserId, () -> {
            UserResource resource = user(keycloakUserId);
            UserRepresentation user = resource.toRepresentation();
            user.setEmail(email);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setEmailVerified(emailVerified);
            resource.update(user);
        });
    }

    @Override
    public void setEnabled(UUID keycloakUserId, boolean enabled) {
        run("set enabled of user " + keycloakUserId, () -> {
            UserResource resource = user(keycloakUserId);
            UserRepresentation user = resource.toRepresentation();
            user.setEnabled(enabled);
            resource.update(user);
        });
    }

    @Override
    public void logoutAllSessions(UUID keycloakUserId) {
        run("end sessions of user " + keycloakUserId, () -> user(keycloakUserId).logout());
    }

    @Override
    public void sendExecuteActionsEmail(UUID keycloakUserId, List<String> actions) {
        run("send execute-actions email to " + keycloakUserId, () -> user(keycloakUserId).executeActionsEmail(
                blankToNull(settings.actionEmailClientId()),
                blankToNull(settings.actionEmailRedirectUri()),
                settings.actionEmailLifespan(),
                List.copyOf(actions)));
    }

    private RealmResource realm() {
        return keycloak.realm(settings.realm());
    }

    private UserResource user(UUID keycloakUserId) {
        return realm().users().get(keycloakUserId.toString());
    }

    private Map<String, RoleRepresentation> realmRolesByName() {
        return realm().roles().list().stream()
                .collect(Collectors.toMap(RoleRepresentation::getName, Function.identity(), (a, b) -> a));
    }

    private KeycloakUserInfo toInfo(UserRepresentation user) {
        List<String> realmRoles = realm().users().get(user.getId()).roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .sorted()
                .toList();
        return new KeycloakUserInfo(
                UUID.fromString(user.getId()),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                Boolean.TRUE.equals(user.isEnabled()),
                Boolean.TRUE.equals(user.isEmailVerified()),
                realmRoles);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A lookup: a 404 means "not found" and returns empty. */
    private <T> Optional<T> lookup(String what, Supplier<T> action) {
        return call(what, () -> {
            try {
                return Optional.ofNullable(action.get());
            } catch (NotFoundException e) {
                return Optional.empty();
            }
        });
    }

    private void run(String what, Runnable action) {
        call(what, () -> {
            action.run();
            return null;
        });
    }

    /** Maps every failure to {@link KeycloakConflictException} or {@link KeycloakCallFailedException}. */
    private static <T> T call(String what, Supplier<T> action) {
        try {
            return action.get();
        } catch (KeycloakConflictException | KeycloakCallFailedException e) {
            throw e;
        } catch (WebApplicationException e) {
            int status = e.getResponse() == null ? 0 : e.getResponse().getStatus();
            if (status == Response.Status.CONFLICT.getStatusCode()) {
                throw new KeycloakConflictException("Keycloak refused to " + what + ": 409", e);
            }
            throw new KeycloakCallFailedException("Keycloak failed to " + what + ": HTTP " + status,
                    status == 0 ? null : status, e);
        } catch (RuntimeException e) {
            throw new KeycloakCallFailedException("Keycloak failed to " + what + ": " + e.getClass().getName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), null, e);
        }
    }
}
