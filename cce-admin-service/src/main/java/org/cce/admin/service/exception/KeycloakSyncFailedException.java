package org.cce.admin.service.exception;

/** The 502 {@code KEYCLOAK_SYNC_FAILED}, which always carries its {@link PartialState} (section 4.2). */
public class KeycloakSyncFailedException extends ApiException {

    private final PartialState partialState;

    public KeycloakSyncFailedException(String message, PartialState partialState) {
        super(ErrorCode.KEYCLOAK_SYNC_FAILED, message);
        this.partialState = partialState;
    }

    public PartialState partialState() {
        return partialState;
    }
}
