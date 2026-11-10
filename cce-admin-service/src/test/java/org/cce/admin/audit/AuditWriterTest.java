package org.cce.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Section 8: the writer's two transaction modes, and the actor outside a request. */
class AuditWriterTest extends DatabaseTestBase {

    @Autowired
    private AuditWriter auditWriter;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void recordWithoutATransactionFails() {
        UUID entityId = UUID.randomUUID();
        assertThatThrownBy(() -> auditWriter.record(
                AuditEntry.success(AuditAction.ROLE_UPDATED, AuditEntityType.ROLE, entityId, null, Map.of("a", 1))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void recordJoinsTheCurrentTransactionAndRollsBackWithIt() {
        UUID entityId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            auditWriter.record(AuditEntry.success(AuditAction.ROLE_UPDATED, AuditEntityType.ROLE, entityId,
                    null, Map.of("a", 1)));
            status.setRollbackOnly();
        });
        assertThat(auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.ROLE, entityId)).isEmpty();
    }

    @Test
    void recordInNewTransactionSurvivesTheOuterRollback() {
        UUID entityId = UUID.randomUUID();
        UUID[] id = new UUID[1];
        tx.executeWithoutResult(status -> {
            id[0] = auditWriter.recordInNewTransaction(AuditEntry.failed(AuditAction.USER_CREATED,
                    AuditEntityType.USER, entityId, null, null,
                    Map.of("operation", "USER_CREATE", "failedStep", "LOCAL_USER_SAVED",
                            "completedSteps", List.of("KEYCLOAK_USER_CREATED"))));
            status.setRollbackOnly();
        });
        List<AuditLogEntity> rows = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER, entityId);
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getId()).isEqualTo(id[0]);
        assertThat(row.getAction()).isEqualTo("USER_CREATED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(row.getFailure().path("failedStep").asText()).isEqualTo("LOCAL_USER_SAVED");
        assertThat(row.getFailure().path("completedSteps").get(0).asText()).isEqualTo("KEYCLOAK_USER_CREATED");
    }

    @Test
    void theActorOutsideARequestIsSystemBootstrap() {
        UUID entityId = UUID.randomUUID();
        auditWriter.recordInNewTransaction(AuditEntry.success(AuditAction.USER_ROLES_UPDATED, AuditEntityType.USER,
                entityId, Map.of("roles", List.of()), Map.of("roles", List.of("SUPER_ADMIN"))));
        AuditLogEntity row = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER, entityId).get(0);
        assertThat(row.getActorUsername()).isEqualTo(ActorProvider.SYSTEM_BOOTSTRAP);
        assertThat(row.getActorUserId()).isNull();
        assertThat(row.getActorKeycloakId()).isNull();
        assertThat(row.getAfterState().path("roles").get(0).asText()).isEqualTo("SUPER_ADMIN");
        assertThat(row.getBeforeState().path("roles").isArray()).isTrue();
    }
}
