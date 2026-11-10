package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.cce.admin.client.SyncResult;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.SyncStatus;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 5.10 and AD-06: each attempt marks only the asking role and writes one ROLE_SYNC_REQUESTED row. */
class GatewaySyncServiceTest extends DatabaseTestBase {

    @Autowired
    private GatewaySyncService gatewaySyncService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuditLogRepository auditLog;

    private RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName(name);
        role.setSystem(false);
        role.setAssignable(true);
        RoleEntity saved = roleRepository.save(role);
        // An updated_at well in the past, so a change would be visible.
        jdbcTemplate.update("UPDATE roles SET updated_at = '2026-01-01T00:00:00Z' WHERE id = ?", saved.getId());
        return roleRepository.findById(saved.getId()).orElseThrow();
    }

    @Test
    void aSuccessfulSyncMarksOnlyTheAskingRoleSyncedAndWritesOneRow() {
        RoleEntity asking = role("SYNC_ASKING");
        RoleEntity other = role("SYNC_OTHER");
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        GatewaySyncService.Outcome outcome = gatewaySyncService.sync(asking.getId());

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.syncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.auditLogId()).isNotNull();

        RoleEntity reloaded = roleRepository.findById(asking.getId()).orElseThrow();
        assertThat(reloaded.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(reloaded.getLastSyncAt()).isEqualTo(outcome.lastSyncAt()).isAfterOrEqualTo(before);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(asking.getUpdatedAt());

        RoleEntity untouched = roleRepository.findById(other.getId()).orElseThrow();
        assertThat(untouched.getSyncStatus()).isEqualTo(SyncStatus.NEVER);
        assertThat(untouched.getLastSyncAt()).isNull();
        assertThat(roleRepository.findByName("SUPER_ADMIN").orElseThrow().getSyncStatus()).isEqualTo(SyncStatus.NEVER);

        assertThat(auditLog.count()).isEqualTo(1);
        List<AuditLogEntity> rows = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.ROLE,
                asking.getId());
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getId()).isEqualTo(outcome.auditLogId());
        assertThat(row.getAction()).isEqualTo("ROLE_SYNC_REQUESTED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getBeforeState()).isNull();
        assertThat(row.getAfterState().path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(row.getAfterState().path("httpStatus").asInt()).isEqualTo(200);
        assertThat(row.getAfterState().has("errorSummary")).isTrue();
        assertThat(row.getAfterState().path("errorSummary").isNull()).isTrue();
        assertThat(row.getActorUsername()).isEqualTo("system:bootstrap");
    }

    @Test
    void aFailedSyncMarksOnlyTheAskingRoleFailedAndWritesOneFailedRow() {
        RoleEntity asking = role("SYNC_FAILING");
        RoleEntity other = role("SYNC_BYSTANDER");
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(false, 503, "HTTP 503: down"));

        GatewaySyncService.Outcome outcome = gatewaySyncService.sync(asking.getId());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.syncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(outcome.errorSummary()).isEqualTo("HTTP 503: down");

        RoleEntity reloaded = roleRepository.findById(asking.getId()).orElseThrow();
        assertThat(reloaded.getSyncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(reloaded.getLastSyncAt()).isEqualTo(outcome.lastSyncAt());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(asking.getUpdatedAt());
        assertThat(roleRepository.findById(other.getId()).orElseThrow().getSyncStatus()).isEqualTo(SyncStatus.NEVER);

        assertThat(auditLog.count()).isEqualTo(1);
        AuditLogEntity row = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.ROLE,
                asking.getId()).get(0);
        assertThat(row.getId()).isEqualTo(outcome.auditLogId());
        assertThat(row.getAction()).isEqualTo("ROLE_SYNC_REQUESTED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(row.getAfterState().path("outcome").asText()).isEqualTo("FAILED");
        assertThat(row.getAfterState().path("httpStatus").asInt()).isEqualTo(503);
        assertThat(row.getAfterState().path("errorSummary").asText()).isEqualTo("HTTP 503: down");
        assertThat(row.getFailure()).isNull();
    }
}
