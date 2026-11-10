package org.cce.admin.service.exception;

import org.springframework.http.HttpStatus;

/** Every error code in section 4.2, with its HTTP status. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    EMAIL_IN_USE(HttpStatus.CONFLICT),
    USERNAME_IN_USE(HttpStatus.CONFLICT),
    DUPLICATE_ROLE_NAME(HttpStatus.CONFLICT),
    DUPLICATE_MENU_KEY(HttpStatus.CONFLICT),
    ROLE_IN_USE(HttpStatus.CONFLICT),
    SYSTEM_ROLE_IMMUTABLE(HttpStatus.CONFLICT),
    LAST_SYSTEM_ADMIN(HttpStatus.CONFLICT),
    ROLE_NOT_ASSIGNABLE(HttpStatus.CONFLICT),
    ROLE_NOT_SYNCED(HttpStatus.CONFLICT),
    MENU_HAS_CHILDREN(HttpStatus.CONFLICT),
    KEYCLOAK_SYNC_FAILED(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
