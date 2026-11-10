package org.cce.admin.audit;

import java.util.UUID;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditOutcome;

/**
 * One audit row to write (section 8). {@code before} and {@code after} are the resource in its API
 * shape (section 4.4); {@code failure} is the failure JSON of rule 3. Each is serialized with the
 * application's {@code ObjectMapper}, and may be null.
 */
public record AuditEntry(
        AuditAction action,
        AuditEntityType entityType,
        UUID entityId,
        AuditOutcome outcome,
        Object before,
        Object after,
        Object failure) {

    public static AuditEntry success(AuditAction action, AuditEntityType entityType, UUID entityId,
            Object before, Object after) {
        return new AuditEntry(action, entityType, entityId, AuditOutcome.SUCCESS, before, after, null);
    }

    public static AuditEntry failed(AuditAction action, AuditEntityType entityType, UUID entityId,
            Object before, Object after, Object failure) {
        return new AuditEntry(action, entityType, entityId, AuditOutcome.FAILED, before, after, failure);
    }
}
