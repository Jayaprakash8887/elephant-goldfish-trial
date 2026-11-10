package org.cce.admin.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.repository.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The only code that inserts into {@code audit_log} (section 8). {@link #record} joins the current
 * transaction and fails if there is none (rule 1). {@link #recordInNewTransaction} runs in its own
 * transaction (rules 2 and 3). Both return the new row's id.
 */
@Component
public class AuditWriter {

    private final AuditLogRepository repository;
    private final ActorProvider actorProvider;
    private final ObjectMapper objectMapper;

    public AuditWriter(AuditLogRepository repository, ActorProvider actorProvider, ObjectMapper objectMapper) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(AuditEntry entry) {
        return insert(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordInNewTransaction(AuditEntry entry) {
        return insert(entry);
    }

    private UUID insert(AuditEntry entry) {
        ActorProvider.Actor actor = actorProvider.current();
        AuditLogEntity row = new AuditLogEntity();
        row.setActorUserId(actor.userId());
        row.setActorKeycloakId(actor.keycloakId());
        row.setActorUsername(actor.username());
        row.setAction(entry.action().name());
        row.setEntityType(entry.entityType());
        row.setEntityId(entry.entityId());
        row.setOutcome(entry.outcome());
        row.setBeforeState(toJson(entry.before()));
        row.setAfterState(toJson(entry.after()));
        row.setFailure(toJson(entry.failure()));
        row.setIpAddress(clientIp());
        return repository.save(row).getId();
    }

    private JsonNode toJson(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    /** The request's remote address after Tomcat's forwarded-header handling (AD-19), or null. */
    private static String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest().getRemoteAddr();
        }
        return null;
    }
}
