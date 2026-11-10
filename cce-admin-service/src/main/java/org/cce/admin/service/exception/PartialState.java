package org.cce.admin.service.exception;

import java.util.List;
import java.util.UUID;

/**
 * The {@code partialState} of a 502 {@code KEYCLOAK_SYNC_FAILED} (section 4.2). The step names are
 * those of the flows in section 5. {@code keycloakUserId} and {@code auditLogId} may be null.
 */
public record PartialState(
        String operation,
        UUID entityId,
        String failedStep,
        List<String> completedSteps,
        UUID keycloakUserId,
        UUID auditLogId) {

    public PartialState {
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
    }
}
