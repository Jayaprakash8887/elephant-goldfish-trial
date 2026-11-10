package org.cce.admin.client;

/**
 * A Keycloak call failed: any non-2xx status other than a lookup's 404 or a 409, a timeout, or a
 * connection error (section 7). {@code httpStatus} is null when there was no HTTP response.
 */
public class KeycloakCallFailedException extends RuntimeException {

    private final Integer httpStatus;

    public KeycloakCallFailedException(String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
