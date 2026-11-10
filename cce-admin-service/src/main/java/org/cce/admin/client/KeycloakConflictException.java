package org.cce.admin.client;

/** Keycloak answered 409 on a create or an update (section 5, "Keycloak calls"). */
public class KeycloakConflictException extends RuntimeException {

    public KeycloakConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
